# 11 — Processing engine

Status: in-progress
Depends on: [04](../docs/specs/queue-domain-model.md) (done), [05](../docs/specs/process-runner.md) (done), [07](../docs/specs/whisper-transcription.md) (done), [09](../docs/specs/diarization.md) (done), [18](../docs/specs/audio-storage.md) (done), [20](../docs/specs/cleanup-refactors.md) (done)

## Goal

Run the queue: take queued tasks one at a time in queue order and drive each through chunking, transcription, and diarization, honouring queue pause, per-task pause/resume/cancel, and reordering, surviving app restarts, and publishing every state change for live updates.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): all async work via Kotlin coroutines.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): one whisper-cli run per ~5-minute chunk, pause = `SIGSTOP`/`SIGCONT`, resume after restart from the last finished chunk, cooperative pause gate for in-JVM phases.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the original waits in `$STT_DATA_DIR/work/<task-id>/source` until chunking; the chunk FLACs are stored in the database (item 18) and are the only audio afterwards.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): engine tests use Kotest + `kotlinx-coroutines-test`.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): engine events feed the SSE endpoint (item 12).

## Proposal (approved 2026-10-09)

- **Worker.** A single worker coroutine launched in the application `CoroutineScope` (`SupervisorJob`). It takes the QUEUED task with the lowest `queue_position` when the queue is not paused. Wakeups come from a `Channel`/`StateFlow` signalled by enqueue, resume, reorder, queue resume, and task completion — no polling. Concurrency stays 1.
- **Phases per task** (status RUNNING, `phase` column from item 03):
  1. `CHUNKING` — the original is `$STT_DATA_DIR/work/<task-id>/source` (moved there at upload, item 18); if it is missing, the task fails with an error naming the missing original. Item 06 decodes it, yields the duration (decoded sample count), plans and exports the chunk FLACs into the work directory; the engine stores `duration_ms` and stores the plan together with all chunk FLACs in one transaction (item 18), and only then deletes the original. Skipped if the chunk plan already exists in `chunks` (resume reuses the stored chunks and needs no original); a leftover original found then (a crash between the commit and its deletion) is deleted. Per item 06's approved spec (2026-10-06), plan persistence, reuse on resume and the work directory's lifecycle belong here.
  2. `TRANSCRIBING` — chunk loop in `idx` order, skipping chunks already `DONE`; each chunk's FLAC is written from the database to a file in the work directory (item 18) and transcribed by whisper-cli (item 07) with the previous chunk's trailing text as `--prompt`; segments + chunk `DONE` are written in one transaction; transcription progress updated from whisper-cli's `-pp` output weighted by chunk duration (`tasks.progress`).
  3. `DIARIZING` — unless `speakers_mode = off` or the task has no segments: the whole-file PCM is rebuilt from the stored chunk FLACs (each decoded in `idx` order and concatenated, with or without a restart, ADR 0024), then item 09 assigns speakers (`speakerIds`) and stores them (`SegmentRepository.storeSpeakers`). Diarization runs in two stages, each with its own progress: `EMBEDDING` (embedded segments / all segments, `Embeddings.embed`'s callback) and then `CLUSTERING` (merges done / all merges, the `onMergeDone` callback of `speakerIds`).
  4. `COMPLETED` — `finished_at` set, work directory deleted.
- **Queue pause.** Sets `queue_state.paused`; the running task's current external process (ffmpeg during `CHUNKING`, whisper-cli during `TRANSCRIBING`) is `SIGSTOP`ped (item 05) or its pause gate closed (in-JVM phase), and nothing new is dequeued. Queue resume reverses both.
- **Task pause / resume.** Same mechanism for that one task, in every phase (ffmpeg is stopped during `CHUNKING` too). While the running task is paused, the worker **waits**: no other task starts, so two whisper-cli processes never compete for one GPU. Resuming a task paused mid-run continues **that task directly** (`PAUSED → QUEUED → RUNNING` at once, item 04's table), even if another QUEUED task is ahead of it in the queue. Pausing a QUEUED task only changes its status and does not block the worker.
- **Cancel.** Cancels the task's `Job`; the process runner kills the child process (item 05); status CANCELED, `finished_at` set. The cancel can be awaited (returns once the process is gone and the status is stored), so item 12's `DELETE` of a pending task can cancel first and then delete. What stays (consultation 2026-10-09, for retry): the stored chunks and the segments of `DONE` chunks; if the task was not chunked yet, its original in the work directory; everything else in the work directory is removed.
- **Failure.** Any exception other than cancellation (non-zero whisper-cli/ffmpeg exit, unparsable JSON, model load error) → status FAILED with a readable message in `error`; the worker moves on to the next task. The work directory is cleaned up like on cancel: the original stays if the task was not chunked yet; stored chunks and segments stay.
- **Phase column** (item 12 contract, 2026-10-09). `tasks.phase` is set while the task runs. It stays on FAILED and CANCELED tasks (the phase they stopped in, for "failed while transcribing at <time>" together with item 12's `transcribedUntilMs`) and is cleared when a task becomes QUEUED (retry, restart recovery) or COMPLETED.
- **Retry (consultation 2026-10-09; amends item 04).** `FAILED → QUEUED` and `CANCELED → QUEUED` become allowed transitions: the task is appended at the end of the queue, `error` and `finished_at` are cleared, and it then resumes exactly like after a restart: CHUNKING runs again only if no plan is stored (from the kept original; if it is missing, the task fails naming it), `DONE` chunks are skipped, the interrupted chunk is rerun, and diarization runs again from the start (item 09's `storeSpeakers` replaces stored speakers). Item 04's "terminal statuses are final" rule changes: COMPLETED stays final; FAILED and CANCELED become retryable, and the amendment is recorded in [docs/specs/queue-domain-model.md](../docs/specs/queue-domain-model.md) when this item implements it. Retry vs pause: a paused task stays pending, keeps its queue position, holds its stopped process and (once started) makes the worker wait; resume continues the same process with no work lost. A cancelled or failed task leaves the queue and frees the worker; retry puts it at the end of the queue and redoes the interrupted chunk.
  - Code consequence: `TaskStatus.isTerminal` is derived today from "no allowed targets" (`stt/queue/TaskStatus.kt:17-18`), and `TaskQueue` drops terminal tasks from the queue. With retry targets, FAILED and CANCELED would no longer count as terminal, so `isTerminal` must name COMPLETED, FAILED and CANCELED explicitly. Retry then re-enters the queue aggregate through the path that enqueues a new upload.
- **Progress (two bars, consultation 2026-10-09; clustering stage added 2026-10-09).**
  - Transcription progress: 0–1, in `tasks.progress`.
  - Diarization stage: `EMBEDDING`, `CLUSTERING`, or null outside the DIARIZING phase.
  - Diarization progress: 0–1 within the current stage. Before DIARIZING it is 0, and 1 once the speakers are stored. It is null when diarization does not apply (speakers `off`, or the task finished without segments).
  - Stage and diarization progress need new nullable columns through a migration (ADR 0019).
  - Item 12 exposes them as `transcriptionProgress`, `diarizationStage` and `diarizationProgress`. The diarization bar fills once per stage, labelled by stage, with no guessed weights.
- **Restart recovery.** On startup, tasks found RUNNING become QUEUED at their existing `queue_position` and resume from the last `DONE` chunk (segments of an unfinished chunk were never committed, so that chunk is simply rerun). Tasks found PAUSED **stay PAUSED**; on resume the interrupted chunk is transcribed again (its stopped process is gone and its segments were never committed). A PAUSED task that had already started (`started_at` set) **still blocks the worker** after the restart, as before it, until it is resumed or cancelled. Work directories of pending tasks (QUEUED, RUNNING, PAUSED) are kept, because a task not yet chunked holds its only copy of the original there (ADR 0024). For FAILED and CANCELED tasks only an unchunked original is kept (retry needs it); COMPLETED and deleted tasks' work directories are removed.
- **Events.** Every state change is emitted on a `SharedFlow<Event>` (`TaskUpdated` with the full task, `TaskProgress` with transcription progress, diarization stage and diarization progress, `QueueUpdated`, `TaskDeleted`) consumed by the SSE endpoint (item 12, whose contract fixes the payloads). DB writes happen before the corresponding event is emitted.
- **Pause gate placement** (decided 2026-10-09). `PauseGate` (item 08) moves from `stt.diarization` to a new shared infrastructure package, `stt.pause`: an infrastructure feature like `db/` (ADR 0013). The engine and diarization both import it. It is a pure move, with the callers' imports updated (`Embeddings.embed`, item 09's `speakerIds`/`clusterSegments`/`upgmaMerges`).
- **Whisper model path.** `STT_WHISPER_MODEL` is read into `Config` here (left to this item by item 07's spec: the whisper command takes the model path as a parameter). Unset or empty fails fast like `STT_SPEAKER_MODEL`.
- **Clustering speed.** This item uses item 09's clustering as measured: 1000 segments 0.68 s / 8 MiB, 3000 segments 11.4 s / 69 MiB. A faster algorithm is item 23, worked on in parallel. Nothing here waits for it.
- **Testability.** The runner, transcriber, chunker, and diarizer are injected interfaces so engine tests run under `runTest` with fakes and virtual time.

## Open questions (answered 2026-10-09)

- While the running task is paused, does the worker wait or move on? → **Wait.**
- Pausing during `CHUNKING`? → **`SIGSTOP` ffmpeg**, like whisper-cli.
- Tasks that were PAUSED when the app stopped? → **Stay PAUSED; the interrupted chunk is rerun on resume.**
- Progress and diarization? → **Two bars**: transcription and diarization. The diarization bar is hidden in the UI when diarization does not apply (`diarizationProgress = null`). Its stages were changed by the clustering-progress answer below.
- Resuming a task paused mid-run when another QUEUED task is ahead of it? → **That task continues directly.**
- After a restart, does a started PAUSED task still block the worker? → **Yes**, until it is resumed or cancelled.
- Cancel vs delete, and is a cancelled task restartable? → **Cancel stops the task and keeps a CANCELED entry, which can be retried**; delete removes everything.
- What happens to a FAILED task? → **It can be retried, resuming from its stored chunks**; it is never deleted automatically.
- Audio of a task that fails or is cancelled before chunking? → **The original is kept until the task is deleted or chunked.**
- Concurrency? → **1** (item 04's at-most-one-RUNNING rule).
- Clustering cost? → **Faster clustering now, as item 23**, measuring its agreement with the current labels; this item proceeds with the current clustering in parallel.
- Where does `PauseGate` live? → **A shared infrastructure package, `stt.pause`.**
- Use item 09's clustering progress? → **Yes: the diarization bar shows two stages**, `EMBEDDING` and then `CLUSTERING`, each with progress 0–1 (contract change in item 12 and `docs/api/`).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

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
- [ ] Startup cleanup keeps the work directories of pending tasks and the unchunked original of FAILED and CANCELED tasks, and removes the rest for terminal or deleted tasks
- [ ] Diarization uses the PCM rebuilt from the stored chunks, with and without a restart
- [ ] A task with `speakers = off` completes without the diarization phase, and its segments keep `speaker_id` NULL with no speakers stored (moved from item 09, 2026-10-09)
- [ ] A task with no speech segments completes without diarization
- [ ] While a started task is paused, no other task starts, also after a restart; resuming continues that task directly, even if another QUEUED task is ahead of it
- [ ] Pausing during chunking stops ffmpeg; resuming continues it
- [ ] After a restart, a task that was PAUSED stays PAUSED; on resume it reruns its interrupted chunk
- [ ] Diarization reports stage `EMBEDDING` with progress per embedded segment, then `CLUSTERING` with progress per merge, ends with no stage and progress 1, and has null progress when diarization does not apply
- [x] Configuration fails at startup naming `STT_WHISPER_MODEL` when it is unset or empty, and carries its path when set
- [ ] Cancelling can be awaited: it returns once the process is gone and CANCELED is stored
- [ ] Cancelling or failing before chunking keeps the original; after chunking, the stored chunks and the segments of DONE chunks are kept
- [ ] Retrying a FAILED or CANCELED task queues it at the end, clears its error, skips its DONE chunks and reruns the interrupted chunk
- [ ] Retrying a task that failed before chunking chunks it again from the kept original
- [ ] A COMPLETED task cannot be retried
- [x] `TaskStatus`'s transitions, retry included, equal `docs/api/task-transitions.json`, and every command there maps to an allowed transition (item 12 contract, added 2026-10-09)
- [ ] A failed or cancelled task keeps the phase it stopped in; it is cleared on retry, on restart recovery and on completion
- [ ] Every status, phase, and progress change, and every deletion, is emitted as an event after it is persisted
- [ ] The worker waits for work without polling (no wakeups while idle under virtual time)

## Acceptance criteria

- Engine tests run under `runTest` with fake runner/transcriber/diarizer cover ordering, reorder mid-run, pause/resume, cancel, restart recovery, and the failure path.
- No `Thread`, `ExecutorService`, `CompletableFuture`, or polling loop in the engine; blocking calls only inside `withContext(Dispatchers.IO)`.
- A tagged `Integration` test runs one short fixture clip end-to-end through real ffmpeg + whisper-cli + diarization to COMPLETED with segments stored.
- The CAM++ ONNX session (`SpeakerModel`, item 08) is created once per application run and reused for every task (moved from item 08, 2026-10-08).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: consultation answers on UI-visible behavior approved (item 12/13 contract consultation): the worker waits while the running task is paused; ffmpeg is `SIGSTOP`ped during chunking; PAUSED tasks stay PAUSED across a restart and rerun the interrupted chunk; two progress values (transcription; diarization = embedded segments / all, 1 while clustering, null when not applicable); awaitable cancel for item 12's `DELETE`; `TaskDeleted` event. The rest of the item is still draft.
- 2026-10-09: further answers: resuming a task paused mid-run continues that task directly; a started PAUSED task still blocks the worker after a restart.
- 2026-10-09: decisions on cancel and failure: cancel keeps a CANCELED entry; FAILED and CANCELED tasks can be retried (`→ QUEUED`, at the end of the queue, resuming from stored chunks like a restart; amends item 04's "terminal is final" for these two); an unchunked original is kept until the task is deleted or chunked; partial results of unfinished tasks are not viewable or downloadable (item 12).
- 2026-10-09: order approved by the user: this item starts after item 20 merges, because retry changes the same `TaskStatus` transition code that item 20's R7 rewrites (`transition` → `canTransitionTo`).
- 2026-10-09: phase rule from item 12's contract: kept on FAILED/CANCELED, cleared on QUEUED and COMPLETED.
- 2026-10-09: item 13 consultation: the transition table is shared as `docs/api/task-transitions.json`; this item adds the backend check that `TaskStatus` matches it.
- 2026-10-09: consultation answers approved: concurrency 1; faster clustering split out as item 23, in parallel, while this item uses item 09's clustering; `PauseGate` moves to a shared infrastructure package `stt.pause`; the diarization bar shows two stages (`EMBEDDING`, `CLUSTERING`) with progress within each (contract change in item 12 and `docs/api/`); `STT_WHISPER_MODEL` read into `Config` here (from item 07's spec). Test list not yet approved.
- 2026-10-09: test list (32 items) approved by the user; status `spec-approved`.
- 2026-10-09 (cycle 1, test-list item 29, the transition contract): red — `gradle test --tests 'stt.queue.TaskTransitionsContractSpec'`: both tests fail, "FAILED → QUEUED expected:<true> but was:<false>" and "retry: FAILED → QUEUED expected:<true> but was:<false>". Green: `FAILED to setOf(QUEUED)` and `CANCELED to setOf(QUEUED)` in `TaskStatus.allowedTargets`, and `isTerminal` now names `COMPLETED`, `FAILED`, `CANCELED` explicitly (a private `terminalStatuses` set) instead of deriving them from "has no allowed targets" — without that, `TaskQueue` would keep a FAILED/CANCELED task and item 04's "a task that fails or is canceled also leaves the queue" test would break. `TestTransitions.kt` (the hand table behind `TaskStatusSpec`) gains the two retry pairs. The new `TaskTransitionsContractSpec` reads `docs/api/task-transitions.json` (resolved from `backend/`, the Gradle test working directory), checks every status pair against the contract's `transitions` (both directions, plus that the status names are the same set) and every `commands` entry's `to` against its `from` list. Amendment recorded in `docs/specs/queue-domain-model.md`. `gradle check` (133 unit tests, 0 failures) green; no `Integration` test is affected. No new ADR. No refactoring (user).
- 2026-10-09 (cycle 2, test-list item 23, `STT_WHISPER_MODEL`): red — `gradle test --tests 'stt.config.ConfigSpec'`: compilation failed, "Unresolved reference 'whisperModel'" at `ConfigSpec.kt:95` (the new `given` mirrors the `STT_SPEAKER_MODEL` one: unset and empty fail, a set path is carried). Green: `Config.whisperModel: Path` (no default, like `speakerModel`), `WHISPER_MODEL_MISSING_ERROR` ("STT_WHISPER_MODEL must be set to the whisper.cpp model file") and the `require` in `fromEnv`; `TEST_WHISPER_MODEL` added to `TestConfig.kt` and to `TEST_REQUIRED_ENV`, so every existing `Config.fromEnv(TEST_REQUIRED_ENV)` test keeps loading. Direct `Config(...)` constructions gained `whisperModel = Path.of(TEST_WHISPER_MODEL)`: `MainSpec` (2), `TestAppConfig.testConfig`, `DatabaseDependencySpec` (3, reformatted to named arguments), and `StaticDisabledSpec`'s explicit env map. `gradle check` (134 unit tests) and `gradle integrationTest` (46 run, 134 skipped by the tag filter) green — the latter matters because the Integration specs build a `Config` from `System.getenv()`, which the devenv shell provides. No new ADR.
- 2026-10-09 (refactor 1 of 2, after cycle 2, approved by the user): `PauseGate` moves from `stt.diarization` to a new infrastructure package `stt.pause` (`git mv`, so history follows), as the roadmap's "pause gate placement" decision requires: the engine must close the same gate the diarizer checks, and it is infrastructure like `db/` (ADR 0013). Pure move: class, visibility (`internal`), members and KDocs unchanged apart from the package and a sentence naming both users. Callers gained one import each: `Clustering.kt`, `Embeddings.kt`, `SpeakerIds.kt`, `EmbeddingsSpec.kt`, `SpeakerIdsSpec.kt`. No behavior change.
