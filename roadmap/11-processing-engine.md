# 11 — Processing engine

Status: draft
Depends on: [04](../docs/specs/queue-domain-model.md) (done), [05](../docs/specs/process-runner.md) (done), [07](../docs/specs/whisper-transcription.md) (done), [09](09-diarization.md), [18](../docs/specs/audio-storage.md) (done)

## Goal

Run the queue: take queued tasks one at a time in queue order and drive each through chunking, transcription, and diarization, honouring queue pause, per-task pause/resume/cancel, and reordering, surviving app restarts, and publishing every state change for live updates.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): all async work via Kotlin coroutines.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): one whisper-cli run per ~5-minute chunk, pause = `SIGSTOP`/`SIGCONT`, resume after restart from the last finished chunk, cooperative pause gate for in-JVM phases.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the original waits in `$STT_DATA_DIR/work/<task-id>/source` until chunking; the chunk FLACs are stored in the database (item 18) and are the only audio afterwards.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): engine tests use Kotest + `kotlinx-coroutines-test`.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): engine events feed the SSE endpoint (item 12).

## Proposal (pending approval)

- **Worker.** A single worker coroutine launched in the application `CoroutineScope` (`SupervisorJob`). It takes the QUEUED task with the lowest `queue_position` when the queue is not paused. Wakeups come from a `Channel`/`StateFlow` signalled by enqueue, resume, reorder, queue resume, and task completion — no polling. Concurrency stays 1.
- **Phases per task** (status RUNNING, `phase` column from item 03):
  1. `CHUNKING` — the original is `$STT_DATA_DIR/work/<task-id>/source` (moved there at upload, item 18); if it is missing, the task fails with an error naming the missing original. Item 06 decodes it, yields the duration (decoded sample count), plans and exports the chunk FLACs into the work directory; the engine stores `duration_ms` and stores the plan together with all chunk FLACs in one transaction (item 18), and only then deletes the original. Skipped if the chunk plan already exists in `chunks` (resume reuses the stored chunks and needs no original); a leftover original found then (a crash between the commit and its deletion) is deleted. Per item 06's approved spec (2026-10-06), plan persistence, reuse on resume and the work directory's lifecycle belong here.
  2. `TRANSCRIBING` — chunk loop in `idx` order, skipping chunks already `DONE`; each chunk's FLAC is written from the database to a file in the work directory (item 18) and transcribed by whisper-cli (item 07) with the previous chunk's trailing text as `--prompt`; segments + chunk `DONE` are written in one transaction; progress updated from whisper-cli's `-pp` output weighted by chunk duration.
  3. `DIARIZING` — unless `speakers_mode = off` or the task has no segments: the whole-file PCM is rebuilt from the stored chunk FLACs (each decoded in `idx` order and concatenated, with or without a restart, ADR 0024), then item 09 assigns speakers and writes `segments.speaker`.
  4. `COMPLETED` — `finished_at` set, work directory deleted.
- **Queue pause.** Sets `queue_state.paused`; the running task's current external process is `SIGSTOP`ped (item 05) or its pause gate closed (in-JVM phase), and nothing new is dequeued. Queue resume reverses both.
- **Task pause / resume.** Same mechanism for that one task; status PAUSED / back to RUNNING per item 04's state machine. Pausing a QUEUED task only changes its status.
- **Cancel.** Cancels the task's `Job`; the process runner kills the child process (item 05); status CANCELED, work directory deleted, `finished_at` set.
- **Failure.** Any exception other than cancellation (non-zero whisper-cli/ffmpeg exit, unparsable JSON, model load error) → status FAILED with a readable message in `error`, work directory deleted; the worker moves on to the next task.
- **Restart recovery.** On startup, tasks found RUNNING become QUEUED at their existing `queue_position` and resume from the last `DONE` chunk (segments of an unfinished chunk were never committed, so that chunk is simply rerun). Work directories of pending tasks (QUEUED, RUNNING, PAUSED) are kept, because a task not yet chunked holds its only copy of the original there (ADR 0024); those of tasks that are no longer pending (terminal or deleted) are removed.
- **Events.** Every state change is emitted on a `SharedFlow<Event>` (`TaskUpdated`, `TaskProgress`, `QueueUpdated`) consumed by the SSE endpoint (item 12). DB writes happen before the corresponding event is emitted.
- **Pause gate placement** (added 2026-10-09 from the repository review). `PauseGate` (item 08) lives in `stt.diarization` but has no diarization dependency: it is a generic cooperative checkpoint (`pause`/`resume`/suspending `checkpoint`). The engine opens and closes it for the in-JVM phases (queue pause, task pause), which makes `stt.engine → stt.diarization.PauseGate` a cross-feature import ([backend conventions § Package structure](../docs/conventions/backend.md#package-structure): only through explicit interfaces). Visibility is not the issue (`internal` is module-wide). This item decides where it lives and moves it if needed (callers: `Embeddings.embed`, item 09's clustering).
- **Testability.** The runner, transcriber, chunker, and diarizer are injected interfaces so engine tests run under `runTest` with fakes and virtual time.

## Open questions for the user

- While the running task is paused, does the worker wait (proposed: wait, avoids two whisper-cli processes on one GPU) or move on to the next queued task?
- Concurrency stays 1 — confirm.
- Pausing during `CHUNKING`: also `SIGSTOP` the ffmpeg process, or let chunking finish and pause before the first chunk?
- What happens on startup to tasks that were PAUSED when the app stopped (their stopped process is gone): stay PAUSED and rerun the interrupted chunk on resume (proposed)?
- Progress weighting: should diarization get its own share of the progress bar (e.g. a fixed fraction), or does progress reach 100 % at the end of transcription?
- Clustering cost on long recordings (item 09 ports the oracle's `O(n²)`-memory, `O(n³)`-time UPGMA and records its measured time and memory at 1000 and 3000 segments): acceptable for the engine, or revisit it if performance becomes an issue (item 09 consultation, 2026-10-09)?
- Where does `PauseGate` live? Options: (a) a small shared infrastructure package (e.g. `stt.pause`, like `db/` an infrastructure feature, ADR 0013), used by the engine and diarization; (b) the engine's package, with diarization taking the gate as a parameter (diarization then imports from the engine); (c) stays in `stt.diarization` and the engine imports it, an accepted exception recorded here.

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Queued tasks are processed one at a time in `queue_position` order
- [ ] Reordering while a task runs changes which task starts next, without interrupting the running one
- [ ] A paused queue starts no new task; resuming it continues with the next queued task
- [ ] Pausing the queue suspends the running task's process and resuming the queue continues it
- [ ] Pausing and resuming a running task suspends and continues its current process
- [ ] Pausing during diarization stops at the pause gate and resumes from there
- [ ] Cancelling a running task kills its process, marks it CANCELED, and the worker starts the next task
- [ ] Cancelling a queued task marks it CANCELED and it is never started
- [ ] A failing chunk marks the task FAILED with a message and the worker moves on
- [ ] After a restart, a task that was RUNNING is queued again and skips its DONE chunks
- [ ] Chunking stores the plan and every chunk FLAC in one transaction and deletes the original only after that commit
- [ ] A task whose original is missing when chunking starts fails with an error naming the missing original
- [ ] After a restart, a task chunked before the restart resumes from its stored chunks without its original
- [ ] A task whose plan is already stored but whose original was left behind (crash before its deletion) has the original deleted when it resumes
- [ ] Startup cleanup keeps the work directories of pending tasks and removes those of terminal or deleted tasks
- [ ] Diarization uses the PCM rebuilt from the stored chunks, with and without a restart
- [ ] A task with `speakers = off` completes without the diarization phase, and its segments keep `speaker` NULL (moved from item 09, 2026-10-09)
- [ ] A task with no speech segments completes without diarization
- [ ] Every status, phase, and progress change is emitted as an event after it is persisted
- [ ] The worker waits for work without polling (no wakeups while idle under virtual time)

## Acceptance criteria

- Engine tests run under `runTest` with fake runner/transcriber/diarizer cover ordering, reorder mid-run, pause/resume, cancel, restart recovery, and the failure path.
- No `Thread`, `ExecutorService`, `CompletableFuture`, or polling loop in the engine; blocking calls only inside `withContext(Dispatchers.IO)`.
- A tagged `Integration` test runs one short fixture clip end-to-end through real ffmpeg + whisper-cli + diarization to COMPLETED with segments stored.
- The CAM++ ONNX session (`SpeakerModel`, item 08) is created once per application run and reused for every task (moved from item 08, 2026-10-08).
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
