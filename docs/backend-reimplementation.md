# Backend reimplementation spec (current state, for a Rust rewrite driven with Canon TDD)

This document describes the backend exactly as it behaves today, so it can be reimplemented
from scratch — the intended use is a Rust rewrite, driven by the author with Canon TDD and no
agentic coding assistance. It is a behavioral contract: implementation choices (Kotlin, Ktor,
Exposed, Flyway, coroutines) are not requirements. Everything here is current state; historical
migrations and superseded designs are deliberately omitted.

Authoritative sources used to write and check this document:

- `docs/api/` — one example JSON per response, event and error; the frontend's mock server and
the backend's shape checks use these bytes. Treat them as the wire contract.
- `docs/oracle/transcribe.py` — the behavioral oracle for decoding, chunking, transcription,
diarization and output formats. Read it, never run it from the app.
- The current backend code and tests under `backend/src/` — the authority for exact strings
  (error messages, headers, log lines) and edge cases where this document is summarized.
- `docs/specs/*.md` — detailed current behavior per area (their history sections can be ignored).
- `docs/api/openapi.yaml` — the machine-readable form of the API contract.
- `docs/backend-schema.sql` — the consolidated current schema DDL (fresh databases only; see
  section 3 for opening an existing file).

**Baseline:** the current `main` at `52e99c3b` (2026-10-20). **This document is a derived catalog,
not a competing contract:** where it and an authority disagree, the authority wins.

**Traceability** (area → current authority → tests/fixtures):

| Area | Current authority | Tests / fixtures |
| --- | --- | --- |
| Configuration, DB opening | `stt/config/Config.kt`, `stt/db/Database.kt`, the current schema | `ConfigSpec`, `DatabaseSpec` |
| Data model | the current schema (tables above), repositories in `stt/db/` | repository specs |
| HTTP/SSE | `docs/api/*.json`, `docs/specs/http-api-and-sse.md` | route specs, `e2e/tests/14-contract.spec.ts` |
| Upload | `stt/http/UploadRoutes.kt`, `stt/db/StagedUpload.kt` | upload specs, `e2e/tests/41-upload-hardening.spec.ts` |
| Queue domain | `stt/queue/TaskStatus.kt`, `stt/queue/TaskQueue.kt` | `TaskStatusSpec`, `TaskQueueSpec`, `TaskTransitionsContractSpec` |
| Engine | `stt/engine/ProcessingEngine.kt`, `docs/specs/{processing-engine,worker-stability,atomic-task-transitions}.md` | `ProcessingEngine*Spec` |
| Audio/clip/chunks | `stt/audio/*.kt`, `docs/specs/{audio-decoding-and-chunking,audio-in-database}.md` | `Audio*Spec`, Integration specs |
| Transcription | `stt/transcribe/*.kt`, `docs/specs/whisper-transcription.md` | `ChunkTranscriberSpec`, `JfkIntegrationSpec` |
| Diarization | `stt/diarization/*.kt`, `docs/specs/{diarization,speaker-embeddings,faster-clustering}.md` | `ClusteringSpec`, `SpeakerIdsSpec`, `EmbeddingsSpec`, LibriSpeech fixtures |
| Outputs | `stt/outputs/*.kt`, `docs/specs/output-formats.md` | `Render*Spec` |
| Settings/probe | `stt/http/SettingsRoutes.kt`, `stt/transcribe/DeviceProbe.kt` | `Settings*Spec` |
| Process control | `stt/process/*.kt`, `docs/specs/process-runner.md` | `ProcessRunnerSpec`, `ProcessTreeSpec` |
| Errors/logging | `docs/specs/{exception-handling-policy,contextual-logging}.md` | `docs/check-exceptions.ts` |
| Fixtures/models | `backend/src/test/resources/`, `nix/models.nix`, `nix/test-fixtures.nix` | — |

## 1. System shape

One JVM process owns everything:

- a Ktor HTTP server serving the built React UI and a JSON API under `/api`;
- one SQLite database file, `$STT_DATA_DIR/stt.db`, holding task state, the transcript, settings
  and the audio itself;
- one processing worker (a coroutine) that takes queued tasks in order and drives each through
  chunking, transcription and diarization;
- two external programs, `ffmpeg` and `whisper-cli`, resolved by name from `PATH`, run as child
  processes in their own process group;
- one in-process ONNX model (CAM++) for speaker embeddings;
- a Server-Sent Events stream for live updates.

The data directory holds only the database (plus SQLite's WAL/SHM files). Transient files live
under a scratch directory in the OS temp dir, one per data directory, emptied at every start.

The current backend targets Linux x86_64 (the packaged app, CI and the tool pins are Linux); this
spec describes that behavior. macOS support exists only as a separate, unmerged effort.

## 2. Configuration (environment)

Read once at startup; invalid or missing required values fail startup with a message naming the
variable. There is no config file.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `STT_DATA_DIR` | yes | — | Directory holding `stt.db`; created when missing. Unset/empty fails. |
| `STT_SPEAKER_MODEL` | yes | — | CAM++ ONNX model file. Unset/empty fails. |
| `STT_WHISPER_MODEL` | yes | — | whisper.cpp model file (default build: `ggml-large-v3-turbo-q5_0`). Unset/empty fails. |
| `STT_PORT` | no | `8080` | Integer 1–65535; invalid fails. |
| `STT_HOST` | no | `0.0.0.0` | Bind address. The desktop app sets `127.0.0.1`. |
| `STT_STATIC_DIR` | no | — | Built frontend directory; unset disables static serving (and logs a warning). |
| `STT_MAX_UPLOAD_BYTES` | no | 1 GiB (1073741824) | Positive integer; zero/negative/non-numeric fails naming the variable. |

The compute device and thread count are **not** environment configuration; they are stored
settings (section 11). `STT_DEVICE` and `STT_THREADS` are not read.

Database opening: one writer connection (write transactions serialized behind a lock) and a
fresh read-only connection per read transaction; `PRAGMA journal_mode=WAL`, foreign keys on,
`journal_size_limit=67108864` on the writer. A data directory that cannot be created/opened, or
WAL that cannot be enabled, fails startup naming `STT_DATA_DIR`. The scratch directory is
`<OS temp>/stt-<hash of STT_DATA_DIR>/` and is emptied at startup.

## 3. Data model (current tables)

All timestamps are INTEGER epoch milliseconds UTC. Audio bytes are stored in the database; no
files. `tasks.id` is the task id used everywhere.

`tasks`: `id PK`, `original_filename`, `content_type`, `duration_ms NULL`, `status`, `phase NULL`,
`queue_position NULL`, `language`, `speakers_mode`, `speakers_count NULL`, `max_speakers`,
`model`, `progress REAL`, `error NULL`, `created_at`, `started_at NULL`, `finished_at NULL`,
`diarization_stage NULL`, `diarization_progress REAL NULL`.

- `model` is the per-task whisper model name carried in the row (the whisper binary path comes from
  `STT_WHISPER_MODEL`); the API does not expose it. **Compatibility:** behavioral equivalence is
  the requirement; database compatibility matters only if the rewrite must open an existing
  `stt.db`, in which case keep every column above (including `model`) and its values. A fresh
  database may omit `model` if nothing consumes it.

- `status ∈ QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED`.
- `phase ∈ CHUNKING, TRANSCRIBING, DIARIZING`; kept on FAILED/CANCELED, cleared on QUEUED and
  COMPLETED; null for QUEUED/COMPLETED.
- `speakers_mode ∈ auto, off, fixed`; `speakers_count` is 1–20 only with `fixed`, else null.
- `max_speakers` 1–20. `language` is a code from `docs/api/languages.json` or `auto`.
- `diarization_stage ∈ EMBEDDING, CLUSTERING` only during DIARIZING, else null.

`audio`: `task_id PK/FK`, `size_bytes`, `sha256 UNIQUE` (the duplicate rule: at most one task per
audio content; the unique constraint is the authority).

`original_audio`: `id PK`, `task_id UNIQUE FK NULL` — the upload's original bytes are staged here
while arriving (task_id NULL), then attached to the new task in one transaction. Deleted once the
canonical clip is stored, and on every failed/rejected/cancelled upload.

`original_audio_part`: `original_id FK`, `seq >= 0`, `bytes BLOB`, PK `(original_id, seq)`; windows
of at most 1 MiB, in upload order.

`audio_header`: `task_id PK/FK`, `header BLOB` — the canonical clip's FLAC header.
`audio_second`: `task_id FK`, `second >= 0`, `frames >= 1`, `bytes BLOB`, PK `(task_id, second)` —
four 4000-sample frames per row (one second), the last row partial. Header + rows in order are
the encoded FLAC file byte for byte.

`chunks`: `task_id FK`, `idx`, `start_ms`, `end_ms`, `status ∈ PENDING, DONE`, PK `(task_id, idx)`.
Chunks are windows into the canonical clip; no per-chunk audio exists.

`segments`: `id PK`, `task_id FK`, `chunk_idx`, `seq`, `start_ms`, `end_ms`, `text`, `speaker_id
NULL`, UNIQUE `(task_id, chunk_idx, seq)`, FK `(task_id, speaker_id) → speakers(task_id,
speaker_id)`. `speaker_id` is 0-based; null until diarization and with speakers off. Text is stored
exactly as whisper-cli wrote it (leading space included); trimming happens on the way out.

`speakers`: `task_id FK`, `speaker_id >= 0`, `speaker TEXT NOT NULL`, PK `(task_id, speaker_id)` —
written by the app as `Speaker N`, N = speaker_id + 1.

`speaker_merge`: `task_id FK`, `merged_id`, `into_id`, PK `(task_id, merged_id)` — one row per
speaker folded into another; the mapping is resolved when reading, so it can be undone. It is
flat: an `into_id` is never itself a `merged_id` of the same task.

`queue_state`: single row (`id = 1`), `paused 0|1`.
`settings`: single row (`id = 1`), `device`, `threads NULL`, `default_language`, `speakers_mode`,
`speakers_count NULL`, `max_speakers`, `device_fallback NULL`; see section 11.

Deleting a task cascades to its audio, original, clip, chunks, segments, speakers and merges,
freeing its `sha256` for re-upload.

**Fresh database vs an existing file.** `docs/backend-schema.sql` is the consolidated current
DDL; use it only to create a fresh Rust-owned database. An existing Kotlin/Flyway database is
opened **unchanged** — never create or alter tables and never use `CREATE TABLE IF NOT EXISTS`
against it — after validating that `flyway_schema_history` exists with version 12 as the highest
installed version and that every table and column above exists with these names; otherwise refuse
to start naming the file. If the Kotlin app must later reopen a Rust-created database, run the real
Flyway migrations (with a proper baseline row); there is no migration-free shortcut.

## 4. HTTP and SSE contract

**OpenAPI:** `docs/api/openapi.yaml` (OpenAPI 3.0.3) is the machine-readable form of this
contract for Swagger UI and client generation; the `docs/api/*.json` examples remain the
authoritative wire shapes.

JSON only, UTF-8; errors are `{ "error": "<code>", "message": "..." }` (plus `taskId` for
`duplicate-audio`); no stack traces. Every success and error shape has a file in `docs/api/` and
must match it (keys, types, nullability).

### Endpoints

| Method/path | Request | Success | Notes |
| --- | --- | --- | --- |
| `POST /api/tasks` | multipart: `file`; fields `language` (default `auto`), `speakers` (`auto|off|n`, 1–20), `maxSpeakers` (default 8, 1–20) | `201` Task | One file part. Streams the upload into the DB. |
| `GET /api/tasks` | — | `200` `{tasks:[Task]}` | Pending first in queue order, then finished newest `finishedAt` first; no audio bytes. |
| `GET /api/tasks/{id}` | — | `200` Task | Unknown/non-numeric id → `404 not-found`. |
| `POST /api/tasks/{id}/pause` | — | `200` Task | Applies to the running task; illegal → `409 illegal-transition`. |
| `POST /api/tasks/{id}/resume` | — | `200` Task | PAUSED → QUEUED. A task paused **mid-run** (its run is still registered) continues that run directly; one paused while queued, or after a restart, waits in queue order. |
| `POST /api/tasks/{id}/cancel` | — | `200` Task | Kills the process/awaits; keeps stored chunks/segments. |
| `POST /api/tasks/{id}/retry` | — | `200` Task | FAILED/CANCELED → QUEUED at the end; resumes from stored chunks. |
| `DELETE /api/tasks/{id}` | — | `204` | Cancels a pending task first, then deletes; unknown → `404`. |
| `GET /api/queue` | — | `200` Queue | `{paused, taskIds}` (pending ids in order). |
| `POST /api/queue/pause` / `resume` | — | `200` Queue | Freezes/resumes the running process; starts nothing while paused. |
| `PUT /api/queue/order` | `{taskIds:[...]}` | `200` Queue | Must be exactly the non-terminal ids; else `409 reorder-mismatch`. |
| `GET /api/tasks/{id}/turns` | — | `200` Turns | COMPLETED only, else `409 not-completed`. |
| `GET /api/tasks/{id}/transcript?format=txt|srt|vtt` | — | file | COMPLETED only; unknown format `400 invalid-transcript-format`; `Content-Disposition: attachment; filename="<stem>.<ext>"`. |
| `GET /api/tasks/{id}/audio` | — | FLAC | The stored canonical clip, `audio/flac`, with `Content-Length` and Range (`206`, `Content-Range`, `Accept-Ranges`); no clip → `404 no-audio`. |
| `PUT /api/tasks/{id}/speakers/{speakerId}` | `{speaker: string|null}` | `200` Speakers | Rename/reset; validations below. |
| `POST /api/tasks/{id}/speakers/merge` | `{group:[ids], into:id}` | `200` Speakers | Folds members into `into`; mapping only. |
| `DELETE /api/tasks/{id}/speakers/merges` | — | `200` Speakers | Reset all merges. |
| `GET /api/settings` | — | `200` Settings | Includes `availableDevices`, `gpuNames` and `deviceProbeError` (null unless the startup probe failed). |
| `PUT /api/settings` | the settings without `availableDevices`/`gpuNames` | `200` Settings | Validates in order; emits `settings-updated`. |
| `DELETE /api/settings` | — | `200` Settings | Restores the defaults in one write (`auto`, `null`, `auto`, `auto`, `null`, `8`), clears the notice and emits `settings-updated`; idempotent. |
| `DELETE /api/settings/device-fallback` | — | `200` Settings | Clears the notice (also when none); emits `settings-updated`. |
| `GET /api/health` | — | `200` `{status:"ok"}` | — |
| `GET /api/events` | — | `text/event-stream` | Snapshot first, then events; heartbeat every 5 s of silence. |

### Task shape

`id`, `filename`, `status`, `phase` (null on QUEUED/COMPLETED; kept on FAILED/CANCELED),
`language`, `speakersMode` (`auto|off|fixed`), `speakersCount` (number or null), `maxSpeakers`,
`durationMs` (null before chunking), `sizeBytes`, `hasAudio` (whether `audio_header` exists),
`transcriptionProgress` 0–1, `diarizationStage` (`EMBEDDING|CLUSTERING|null`), `diarizationProgress`
(0–1 or null: null when speakers off or no segments; before diarization 0; 1 when stored),
`error` (string|null), `transcribedUntilMs` (end of the last stored segment; null without
segments), `createdAt`, `startedAt`, `finishedAt` (epoch ms or null).

`transcriptionProgress` = (sum of DONE chunk durations + current chunk duration × chunk fraction)
/ total duration; zero total duration reports 0.

### Queue

`{paused: boolean, taskIds: [pending task ids in queue order]}`. Reorder accepts exactly the
current non-terminal ids, each once; rejection precedence duplicate > unknown > missing.

### Turns and speakers

`/turns` → `{speakers:[{speakerId, speaker}], hasMerges: boolean, turns:[{speakerId|null,
startMs, endMs, text, segments:[{startMs,endMs,text}]}]}`. `speakers` are the effective speakers
(excluding merged-away ids), ordered by id. Consecutive segments with the same effective id form a
turn; with speakers off there are no speakers and one turn per segment with `speakerId: null`.
Every segment/turn text is trimmed; a turn's text joins its non-blank trimmed segment texts with
single spaces. Times are milliseconds on the `/audio` timeline.

Speaker rename (`PUT`): name is stored as sent; empty, > 40 chars, or containing a control
character anywhere → `400 invalid-speaker-name`; duplicate (case-insensitive, including merged
speakers) → `409 duplicate-speaker-name`; a speaker's own name is no duplicate; `null` resets to
`Speaker N`; a merged-away speaker → `404 not-found`; a task that is not COMPLETED → `409
not-completed`. Merge: `group` of ≥ 2 ids, `into` in the group, no member already merged, else
`400 invalid-merge`; merged members bring along their own merges. Reset removes all merges. Both
emit `speakers-merged`/`speakers-renamed` and answers the effective speakers.

### Events (`event:` name, `data:` JSON)

- `snapshot` → `{tasks:[Task], queue:Queue}` (first on every connection).
- `task-updated` → full Task; also announces a new upload (then `queue-updated`).
- `task-progress` → `{id, transcriptionProgress, diarizationStage, diarizationProgress}`.
- `queue-updated` → Queue (after every queue-membership change: pause/resume/reorder/leave).
- `task-deleted` → `{id}`.
- `speakers-renamed`/`speakers-merged` → `{id, speakers:[{speakerId,speaker}]}`.
- `heartbeat` → `{}` every 5 s of silence; a client silent for 15 s reconnects and re-snapshots.
- `settings-updated` → the settings without `availableDevices`/`gpuNames`.

A subscriber slower than the event buffer is disconnected (not silently fed a lossy stream), so
the client reconnects and takes a fresh snapshot. Events are emitted after the corresponding
write commits.

### Error codes

`400`: `missing-file`, `unsupported-format`, `invalid-language`, `invalid-speakers`,
`invalid-max-speakers`, `invalid-transcript-format`, `invalid-speaker-name`, `invalid-merge`,
`invalid-device`, `invalid-threads`, `multiple-files`.
`404`: `not-found`, `no-audio`. `409`: `illegal-transition`, `reorder-mismatch`, `duplicate-audio`
(with `taskId`), `not-completed`, `duplicate-speaker-name`. `413`: `upload-too-large` (message
names the limit). `500`: `internal`.

## 5. Upload

`POST /api/tasks` multipart, one file part:

1. Reject a declared `Content-Length` above `STT_MAX_UPLOAD_BYTES` before staging (`413`), and
   enforce the limit while streaming (chunked bodies too); an upload of exactly the limit is
   accepted when chunked. On rejection discard the staged bytes.
2. Validate fields: extension against the allow-list (case-insensitive; content type ignored);
   `language` against `docs/api/languages.json`; `speakers`; `maxSpeakers`. The 19 extensions:
   `mp3 wav flac ogg oga opus m4a aac wma aif aiff amr webm mp4 m4v mov mkv mka 3gp`.
3. Stream the file part into `original_audio`/`original_audio_part` in windows of at most 1 MiB,
   computing `size_bytes` and `sha256` on the way. No spool file and no whole-file buffering.
4. Duplicate check: an existing task with the same `sha256` → `409 duplicate-audio` naming it.
   The `audio.sha256` UNIQUE constraint is the authority: a concurrent identical upload that
   passes the pre-check is translated from the constraint violation to the same `409` (never a
   `500`); exactly one of two simultaneous identical uploads gets `201`.
5. Create the task QUEUED at the end of the queue and its `audio` row in the transaction that
   attaches the staged original; answer `201` Task.
6. Every path — success, rejection, client disconnect/cancel, mid-stream failure — discards the
   staged original (deletion under a non-cancellable scope). A second file part → `400
   multiple-files` and the first is not kept. The startup sweep deletes unclaimed staged
   originals.

## 6. Queue domain and task lifecycle

Allowed transitions (per task):

| From | To |
| --- | --- |
| QUEUED | RUNNING, PAUSED, CANCELED, FAILED |
| RUNNING | PAUSED, COMPLETED, FAILED, CANCELED, QUEUED (process killed; interruption) |
| PAUSED | QUEUED, CANCELED |
| FAILED | QUEUED (retry) |
| CANCELED | QUEUED (retry) |
| COMPLETED | — |

At most one task is RUNNING. The queue aggregate holds only non-terminal tasks; a task reaching a
terminal status leaves it (and stays in the database). A PAUSED task keeps its queue position and
stays reorderable. Reorder rules as above. Pausing an already paused queue / resuming a running
one is a no-op. `QUEUED → RUNNING` is the only transition gated by whole-queue state.

## 7. Processing engine

One worker (concurrency 1), woken by enqueue, resume, reorder, queue resume and task completion;
no polling. It picks the QUEUED task with the lowest `queue_position` when the queue is not
paused; a started PAUSED task blocks the worker until resumed or cancelled.

Per task, status RUNNING with phases:

1. `CHUNKING` — materialize the stored original to a temp file, decode to 16 kHz mono, encode the
   canonical FLAC, plan the chunks, and store the clip (header + one-second rows), the chunk
   windows and `duration_ms` in one transaction; then delete the original. Resume reuses a stored
   plan only with its whole clip; a leftover original is deleted. Missing original → FAILED with
   an error naming it. Clear the phase on success.
2. `TRANSCRIBING` — for each chunk in `idx` order, skip DONE; cut the chunk's samples from the
   clip into a temp FLAC, run whisper-cli (prompt = previous chunk's trailing text), parse the
   JSON, store segments + `DONE` in one transaction; delete the temp input/output. A chunk with
   no speech is DONE with zero segments. Progress as in section 4.
3. `DIARIZING` — skipped when `speakers_mode = off` or the task has no segments. Embed every
   segment from the clip's samples, cluster, store speakers and segment ids in one transaction.
   Stages `EMBEDDING` (windows done / total) then `CLUSTERING` (merges done / total), then no stage
   and progress 1. Diarization runs again from scratch on resume.
4. `COMPLETED` — set `finished_at`, clear phase, remove scratch.

Pause/resume: the running task's current external process is SIGSTOPped/SIGCONTed (ffmpeg during
CHUNKING, whisper-cli during TRANSCRIBING); in-JVM diarization checks a pause gate between
windows/merge iterations. While the running task is paused the worker waits (no second task).
Resuming a task paused mid-run continues that task directly, even if another queued task is ahead;
a task paused while queued (or after a restart) returns to QUEUED and waits in order.
Pausing a QUEUED task only changes its status. Queue pause freezes the running process and starts
nothing new.

Cancel: cancel the task's job, kill its process tree, store CANCELED + `finished_at`; awaitable
(returns once the process is gone and the status is stored), so delete can cancel then delete.
Keep stored chunks and the segments of DONE chunks; keep the original until chunked.

Failure: any non-cancellation exception (non-zero exit, unparsable JSON, model error) → FAILED
with a readable message; the worker moves on. A process killed by SIGTERM while the app keeps
running is **not** a failure: the task goes back to QUEUED at once and is rerun from its
interrupted chunk (RUNNING → QUEUED). A cancel is never a failure.

Start race: the worker's start write is conditional (`QUEUED → RUNNING`); cancel, pause, delete
that arrive while the task is starting act on the run (process stopped/frozen) and the stored
status is not overwritten by a later completion. `setCompleted` is the terminal write; a pause
landing while the last process exits loses to the completion.

Restart recovery: on startup, RUNNING tasks become QUEUED at their existing positions and resume
from the last DONE chunk (an unfinished chunk is rerun; its segments were never committed);
PAUSED tasks stay PAUSED. A started PAUSED task still blocks the worker after restart. The
scratch directory is emptied; unclaimed staged uploads are deleted.

Every state change is persisted before its event is emitted.

## 8. Audio pipeline

Decoding (oracle `decode_pcm`):

```
ffmpeg -hide_banner -nostats -loglevel error -i <input> -f f32le -ac 1 -ar 16000 <pcm-file>
```

Duration = decoded sample count / 16 ms. Non-zero exit → error carrying ffmpeg's stderr; zero
samples → "no audio decoded". Every ffmpeg run uses `-hide_banner -nostats` (the runner splits
stderr on `\n`).

Canonical clip: encode 16 kHz mono with `-c:a flac -sample_fmt s16 -frame_size 4000` (FLAC, 250 ms
frames; the last frame may be shorter; ffmpeg must write to a file, not a pipe, so the STREAMINFO
total is correct). Split into `audio_header` (the FLAC header) and `audio_second` rows (four
frames = one second; row k covers `[k·1000,(k+1)·1000)` ms). The splitter validates frame sync,
header CRC-8, consecutive frame numbers and CRC-16; only the last frame may be shorter than 4000
samples; frames must sum to STREAMINFO's total; anything else is refused as not canonical.

Chunk planning (oracle logic):

```
ffmpeg -hide_banner -nostats -i <input> -af silencedetect=noise=-30dB:d=0.5 -f null -
```

Parse `silence_start`/`silence_end` from stderr. Constants: target 5 min, hard cap 6 min. From the
current start, cut at the midpoint of the detected silence whose midpoint is nearest
`start + target`, considering only silences that end the chunk within the cap; no such silence →
cut at `start + target`. A trailing chunk shorter than 30 s merges into the previous one when the
merged chunk stays within the cap. Chunks are contiguous and cover `[0, durationMs)`.

Whisper input: per chunk, write the clip's header + the rows the window touches to a temp file,
then cut the samples `[fromMs·16, toMs·16)` with one ffmpeg `atrim` run to a 16-bit FLAC (or
pass the exact samples from the decoded clip); delete the file after the run, on success and
failure. The last chunk is included.

Diarization windows: read the clip's one-second rows, decode to 16-bit PCM, feed the embeddings
window by window; never materialize the whole recording.

## 9. Transcription

Per chunk:

```
whisper-cli -m $STT_WHISPER_MODEL -f <chunk-input> -l <language> \
  [-t <threads>] <device-args> -oj -of <prefix> -pp [--prompt <previous tail>]
```

Device args: `auto` → none; `cpu` → `--no-gpu`; `gpu:<i>` → `--device <i>`. `-t` only when the
threads setting is non-null. `-oj` writes `<prefix>.json`; `-pp` prints progress.

JSON: newer shape `{transcription:[{offsets:{from,to},text}]}` (milliseconds; missing offsets → 0,
missing text → ""); legacy `{segments:[{start,end,text}]}` (seconds, must have all three keys;
takes precedence when present; missing keys → malformed). No key/empty → no segments. Stored
times = chunk `start_ms` + offset; `seq` numbers segments from 0 within the chunk. A chunk with
no segments is valid and DONE with zero segments.

Prompt: the previous chunk's segment texts joined and trimmed; keep at most the last 200
characters; if the cut falls inside a word, drop that partial word up to the next whitespace,
then trim. No text → no `--prompt`. On resume rebuild from the stored segments.

Progress: parse `-pp` lines (`whisper_print_progress_callback: progress = NNN%`) into the chunk
fraction; task progress weighted by chunk durations (section 4).

Persistence: segments + chunk `DONE` in one transaction (all or nothing). After commit delete the
chunk input and whisper's JSON (a failed delete is logged, not fatal). Persist the error on
failure: non-zero exit → exit code + full stderr; missing/malformed JSON → error with full stderr.

## 10. Diarization

Embeddings (CAM++, ONNX Runtime, CPU):

- Kaldi fbank: 16 kHz, 80 mel bins, 25 ms frame (400 samples), 10 ms shift (160), povey window,
dither 0, remove DC per frame, pre-emphasis 0.97, low 20 Hz, high 7600 Hz, `snip_edges=false`
(frame count `(samples + 80) / 160`), FFT 512, Kaldi's epsilon floor; per-utterance global-mean
normalization.
- Model metadata read at load: `output_dim` 512, `sample_rate` 16000,
`normalize_samples` true (samples in [−1,1]; false means ×32768), `feature_normalize_type`
global-mean; reject anything else naming key and value. Tensor names from the session.
- Windowing per segment (`sr=16000`, short 2 s = 32000 samples, cap 20 s = 320000, total = clip
sample count): empty audio → all-zero vectors; `t0=start·sr`, `t1=end·sr`; past end/empty → last
2 s; `dur<short` → 2 s zero buffer with the segment centered; `dur>cap` → centered 20 s slice;
otherwise `[t0,t0+dur)`. One window per inference; L2-normalize; a zero vector stays zero; results
in segment order; pause/cancel between windows; ONNX session created once and reused (reload only
on a thread-count change).

Clustering (average linkage):

- Long segments (`endMs − startMs >= 500`) are clustered; if none are long, all are.
- Average linkage (UPGMA) on cosine distance `1 − a·b`, computed in double on unit embeddings:
  iteratively merge the two active clusters with the smallest average pairwise distance; merge
  heights are non-decreasing. The current implementation is a nearest-neighbor chain with
  on-demand distances (no distance matrix); any implementation producing the same merge heights
  is equivalent. Exact height ties are implementation-defined and accepted — they may change the
  merged pair and, at a gap boundary, the automatic count.
- Auto count: if the last merge height `< 0.5448` → 1 speaker; else if n == 2 → 2; else search
the last `max_speakers` gaps for the first largest gap (argmax), `k = n − (index + 1)`, clamp to
`[1, max_speakers]`.
- Fixed count: clamp to `[1, n]`, ignore `max_speakers`.
- Apply the first `n − k` merges with union-find; relabel roots by first appearance in segment
order (0-based).
- Short-segment rescue: for each cluster, centroid = L2-normalized sum of its long-segment
embeddings (zero vector if none); each short segment takes the label of the non-zero centroid with
the highest dot product (first on ties); no centroids → labels unchanged; no relabeling.
- Store one `speakers` row per distinct id (`Speaker N`) and set every segment's `speaker_id` in
one transaction; count mismatch stores nothing; storing again replaces the previous speakers.
- Speaker summary log: per speaker, `speaker N: n segments, within-cos x.xxx` (1.0 for one
segment); with >1 speakers, `cluster-to-cluster cosine: a-b x.xxx  a-c x.xxx …` (three decimals,
Locale.ROOT). Summary failures are logged, never fail the task.

Turns: consecutive segments with the same effective speaker id collapse into one turn (first
start, last end, trimmed non-blank texts joined with single spaces); each turn keeps its segments
with their own trimmed text.

Merges are applied when reading: effective speakers exclude merged ids; a merged id's segments
read as the survivor's; downloads and turns use the effective id and the survivor's name.

## 11. Settings and the device probe

Stored settings (single row, defaults on a fresh database): `device` (`auto|cpu|gpu:<index>`,
default `auto`), `threads` (null = tool default; else ≥ 1), `defaultLanguage` (`auto`),
`speakersMode` (`auto`), `speakersCount` (null), `maxSpeakers` (8), `deviceFallback`
(`gpu:<index>|null`).

`GET` returns the settings plus `availableDevices` (`cpu`, `auto`, then each reported GPU as
`gpu:<i>`), `gpuNames` (`gpu:<i>` → name reported by the probe; `{}` without GPUs) and
`deviceProbeError` (string or null: the startup probe's launch failure, e.g. `Executable not
found: whisper-cli`; present on the GET and the PUT/DELETE responses, absent from the PUT body
and the `settings-updated` event). The probe runs once at startup: whisper-cli is asked for its
devices (it prints `found GPU device …` / `no GPU found` after loading the model); a failed probe
yields no GPU and names the failure, and the app still starts and offers `cpu`/`auto`.

`PUT` validates in this order and stores nothing on a rejection: `device` not `auto`/`cpu`/
`gpu:<i≥0>` → `400 invalid-device` (a well-formed GPU the probe did not report is accepted);
`threads` neither null nor ≥ 1 → `400 invalid-threads`; `defaultLanguage` not in
`languages.json` → `400 invalid-language`; `speakersMode` invalid or `speakersCount` not 1–20 with
`fixed`/not null otherwise → `400 invalid-speakers`; `maxSpeakers` outside 1–20 → `400
invalid-max-speakers`. A stored `PUT` also clears `deviceFallback` and emits `settings-updated`.

Applying: the engine reads the settings once when it claims a task RUNNING (a running task keeps
its snapshot); `device`/`threads` shape whisper-cli and the ONNX session. If a chosen GPU turns
out unavailable while the task runs (whisper-cli reports `no GPU found` and transcribes on the
CPU), the task completes on the CPU, the stored `device` becomes `cpu`, `deviceFallback` names
the GPU, and `settings-updated` is emitted; the notice persists until dismissed, any `PUT`, or
`DELETE /api/settings`. `DELETE /api/settings/device-fallback` clears it (also when none) and emits
`settings-updated`; `DELETE /api/settings` restores every default in the same write and emits
`settings-updated` too.

## 12. Process control

- Each command is spawned as `setsid <cmd…>` (own process group; the direct child's pid is the
group id). Signals target the child pid first (while alive), then the group. The current backend
targets Linux x86_64; another OS needs its own signal numbers and process-group mechanism.
- stdout/stderr drained concurrently line by line on the IO dispatcher; callbacks see lines as
written; the result carries the exit code and both streams; a final line without `\n` is
delivered; a line longer than the read chunk survives; stdin is `/dev/null`.
- `pause` = SIGSTOP (19), `resume` = SIGCONT (18) on the current Linux target; no-ops on an exited process;
a pending pause applies right after spawn; a leftover pause never freezes the next run.
- Cancellation: SIGCONT, SIGTERM, wait grace period (2 s default), SIGKILL; return only once the
tree is dead (or the grace expires with a warning naming the group); rethrow the cancellation.
Each run is single-flight; a second run in flight fails fast.
- A process exiting with 143 (SIGTERM) while the app keeps running surfaces as a cancellation to
the task and means interruption (section 7), not failure.

## 13. Cross-cutting invariants

- Writes that must not race are conditional (`WHERE status = …`) and atomic; a rejected write
reports whether it wrote.
- Events are emitted after the write; `queue-updated` follows every membership change.
- No broad catch swallows a throwable: rethrow, log it, or mark the task; `CancellationException`
  is always rethrown.
- Logging carries the task id (MDC) on every line that concerns a task; named coroutines.
- Scratch and staged uploads are cleaned on every path and at startup; the database is the only
  retained state after a clean stop. A backup is `stt.db` alone (plus WAL/SHM after an unclean
  stop).

## 14. Canon TDD plan (suggested cycle order)

Each list is a behavioral test list for that area; drive them one item at a time (red → green →
refactor question → tick), in this order unless a dependency forces otherwise. Use the published
fixtures and the oracle for expected values.

### Cycle group A — configuration and database

- [ ] Startup fails naming `STT_DATA_DIR` / `STT_SPEAKER_MODEL` / `STT_WHISPER_MODEL` when unset or
      empty; a valid configuration carries the paths and the defaults
- [ ] `STT_PORT` out of range / `STT_MAX_UPLOAD_BYTES` zero, negative or non-numeric fail naming
      the variable; unset values take the defaults
- [ ] Opening a fresh data directory creates it and the database; WAL and foreign keys are on;
      reopening preserves rows; an unopenable directory fails naming `STT_DATA_DIR`
- [ ] Tasks, audio, original parts, clip header/rows, chunks, segments, speakers, merges, queue
      state and settings round-trip; invalid enum values are rejected; `sha256` is unique;
      deleting a task cascades to everything it owns; a second `queue_state`/`settings` row is
      impossible
- [ ] A read transaction completes while a write is held; a write completes while a read is
      stalled; concurrent writes do not interleave; the WAL is bounded by the size limit

### Cycle group B — queue domain

- [ ] Every allowed transition is accepted; every other pair is rejected
- [ ] At most one RUNNING task; a second `QUEUED → RUNNING` is rejected until the first leaves
      RUNNING
- [ ] Reorder accepts exactly the non-terminal ids; duplicates, unknown/terminal ids and missing
      ids are rejected with the agreed precedence; a paused queue stays paused; a PAUSED task can
      be reordered
- [ ] Pausing an already paused queue / resuming a running one is a no-op; a task reaching a
      terminal status leaves the queue

### Cycle group C — upload and staging

- [ ] A valid upload creates a QUEUED task at the end of the queue, records size/sha256, and the
      original is staged in the DB; no file appears anywhere
- [ ] Missing file, a disallowed extension, an invalid language/speakers/maxSpeakers value, and a
      non-numeric maxSpeakers are rejected with the matching 400 code; the default language is
      `auto`; the extension check is case-insensitive
- [ ] The same bytes again → `409 duplicate-audio` naming the existing task; two simultaneous
      identical uploads → exactly one 201 and one 409, never 500
- [ ] An upload above the limit (declared Content-Length and chunked body) → `413
      upload-too-large` naming the limit, no task, no staged bytes; exactly the limit is accepted
- [ ] A second file part → `400 multiple-files`, the first is not kept; every path (success,
      rejection, disconnect, mid-stream failure) leaves no staged original
- [ ] Storage is streamed: the largest buffered window is ≤ 1 MiB and the stored sha256 is the
      true digest

### Cycle group D — audio and chunking

- [ ] Decoding produces 16 kHz mono f32le samples; duration = samples/16; a non-decodable file
      fails with ffmpeg's message; zero samples fail with "no audio decoded"
- [ ] A file shorter than the target is one chunk; no silences → cuts at the target; many
      silences → the midpoint of the silence nearest the target; a silence beyond the cap is never
      chosen; chunks are contiguous and cover `[0,durationMs)`
- [ ] A trailing chunk under 30 s merges into the previous one only within the cap
- [ ] The canonical encode is a valid fixed-4000-frame FLAC; the splitter accepts only valid frames
      that sum to STREAMINFO's total and only a short last frame; a truncated/inconsistent clip is
      refused
- [ ] The clip is stored as header + one-second rows; the rows in order equal the encoded file;
      the original is deleted after the clip is committed; an interrupted chunking resumes by
      re-chunking
- [ ] Whisper input for a chunk decodes to exactly the clip's samples `[from·16, to·16)`, the last
      chunk included, and is deleted on success and failure

### Cycle group E — transcription

- [ ] Both JSON shapes parse with absolute millisecond offsets and trimmed-on-output text; missing
      offsets default to 0; legacy entries missing a key are malformed; no segments is valid
- [ ] `seq` numbers a chunk's segments from 0; segments and chunk DONE are stored all-or-nothing;
      completing a chunk not in the plan fails and stores nothing
- [ ] Device args: `auto` none, `cpu` `--no-gpu`, `gpu:N` `--device N`; `-t` only when threads are
      set; `--prompt` only when a previous tail exists
- [ ] Prompt = last ≤ 200 chars at a word boundary (drop a partial leading word up to any
      whitespace); rebuilt from stored segments on resume
- [ ] `-pp` lines produce the chunk fraction; task progress is weighted by chunk duration; zero
      duration reports 0
- [ ] Non-zero exit fails with the exit code and full stderr; missing/malformed JSON fails with a
      clear message; a failing delete after commit is logged, not fatal

### Cycle group F — diarization

- [ ] Fbank shape `(N+80)/160` frames × 80; povey window, mel edges and a Kaldi reference value
      match; global-mean normalization zeroes column means
- [ ] Model metadata is validated (512, 16000, global-mean); `normalize_samples` selects [−1,1] vs
      ×32768; unsupported metadata names key and value
- [ ] Windowing: short segments centered/zero-padded in 2 s; long segments centered 20 s slice;
      past-end uses the tail; empty audio yields zero vectors; unit length; zero stays zero;
      results in order; pause/cancel between windows
- [ ] Average-linkage merge heights equal hand-computed values; exact ties are resolved
      reproducibly for a given input (the exact pair need not match another implementation);
      auto-k: one speaker below the threshold, two for n=2, largest gap within the last
      `max_speakers` merges, clamped; fixed k clamps to [1,n]; labels by first appearance
- [ ] Short segments (< 500 ms) are not clustered; exactly 500 ms is; all-short clusters all;
      rescue to the nearest non-zero centroid, ties to the lower speaker; no centroids → unchanged
- [ ] Speakers are stored as `Speaker N` in one transaction; count mismatch stores nothing;
      re-storing replaces; turns merge by effective id, keep each segment, trim text; the summary
      logs the expected lines and never fails the task
- [ ] Merges resolve at read time: effective speakers, turns and downloads follow the survivor;
      reset restores everything

### Cycle group G — engine and events

- [ ] The worker takes queued tasks one at a time in queue order; reordering mid-run changes the
      next task; a paused queue starts nothing; a started PAUSED task blocks and resumption
      continues that task directly
- [ ] Pause/resume freezes/continues ffmpeg during CHUNKING and whisper-cli during TRANSCRIBING;
      queue pause also closes the diarization pause gate; cancel kills the tree, stores CANCELED
      and is awaitable; cancelling a queued task never starts it
- [ ] A failing chunk or phase marks FAILED with a message and the worker continues; a failure of
      the failure write leaves RUNNING and logs both errors; a throwing `nextTask` read does not
      kill the worker
- [ ] Restart recovery: RUNNING → QUEUED at its position, DONE chunks skipped, interrupted chunk
      rerun; PAUSED stays PAUSED; a started PAUSED task still blocks; scratch is emptied
- [ ] Retry FAILED/CANCELED → QUEUED at the end, clears error, skips DONE chunks, reruns the
      interrupted chunk; COMPLETED cannot be retried; a process SIGTERM while the app runs →
      QUEUED and rerun; quitting leaves RUNNING for recovery
- [ ] Start race: cancel/pause/delete during the start window act on the run and the stored status
      is not overwritten; a pause during the final process exit loses to COMPLETED
- [ ] Events: every persisted change emits its event in order; `queue-updated` after every
      membership change; a slow subscriber is disconnected; snapshot on connect; heartbeat after
      5 s; reconnecting gets a fresh snapshot

### Cycle group H — HTTP surface

- [ ] Every route answers its `docs/api/` example shape (keys, types, nullability) for success and
      error; unknowns and non-numeric ids are `404 not-found`; illegal commands `409`
- [ ] `GET /api/tasks` orders pending by queue, then finished newest first; `GET /api/queue`
      round-trips pause/resume/order; delete cancels a pending task first and returns 204
- [ ] `/turns`, transcript downloads and speaker operations enforce COMPLETED, the validation
      rules and the 409/400 codes; downloads have the right content type and filename; audio
      serves the clip with Content-Length and Range, and 404 when absent
- [ ] Settings GET/PUT/DELETE behave as section 11, including validation order, events, the
      fallback notice and a failed probe's `deviceProbeError` with cpu/auto offered

### Cycle group I — process control

- [ ] Exit code, stdout/stderr line streaming, final line without newline, long lines, stdin EOF
- [ ] Cancel kills the whole group, including a stopped tree and a descendant holding the pipes;
      a SIGTERM-ignoring process dies after the grace; single-flight; pause-before-spawn and
      leftover pause behavior

## 15. Conformance and equivalence

- **Wire contract**: every response/event/error must match its `docs/api/*.json` example. Reuse the
  examples as golden shapes, and the frontend (already built) against the Rust backend as the
  end-to-end check: serve the built UI and drive the existing e2e journey.
- **Oracle**: transcription, chunk planning, embedding windows, clustering, turns and output
  formats are ported from `docs/oracle/transcribe.py`; derive expected values from the oracle's
  code by hand, never by running Python from the app.
- **Fixtures** (already in the repo): `backend/src/test/resources/librispeech/` (three CC BY 4.0
  clips), `backend/src/test/resources/upload-formats/` (19 per-extension fixtures + 0.5 s
  `sample.flac`), `e2e` clips, and the pinned models (`ggml-tiny.en` for tests, CAM++ for
  embeddings). Reuse them byte-for-byte so expected values transfer.
- **Real-tool tests**: decode/chunk/transcribe/diarize with the real ffmpeg, whisper-cli and
  CAM++ model on the fixtures; the frontend journey (queue → upload → completed → transcript) is
  the end-to-end proof of the API contract.

## 16. External dependencies and Nix artifacts

The runtime dependencies are provided by the flake; a Rust rewrite needs the same binaries,
models and environment contract, not the Kotlin packages. **The normative configuration is the
Nix files themselves** — `flake.nix`, `devenv.nix`, `nix/ffmpeg.nix`, `nix/whisper-cpp.nix`,
`nix/models.nix`, `nix/versions.nix`, `nix/desktop-artifacts.nix`, `nix/prebuilt-artifact.nix`,
`nix/release-asset.nix`; the table below indexes their pins, and the files win on any divergence.

| Dependency | How it is provided | Pin / detail |
| --- | --- | --- |
| nixpkgs | `flake.lock` (one shared pin) | `versions.json` `nixpkgsRev` = `494ce7fd23ff6a5dff39e1fb11e9b6f2ac74bf25` |
| ffmpeg | `nix/ffmpeg.nix`: static audio-only `ffmpeg_8-headless` override (8.1.2; no ffprobe/ffplay; 58 decoders; `flac`/`pcm_f32le`/`null` muxers; `aformat`/`anull`/`anullsink`/`aresample`/`atrim`/`silencedetect`; `file`/`pipe`) | On x86_64-linux the pinned asset `nix/desktop-artifacts.nix` `ffmpeg.x86_64-linux`: url `https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-ffmpeg-v2/ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2-x86_64-linux.tar.zst`, sha256 `945db2902395cfc3a5c002de35fbddc4b3ea14de76ee50d5b3bbfbce98fc184c`, storePath `/nix/store/2pqr6g1ll2dzj7p87cz5a6w8amv2pp22-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`, references `[]`; other systems build from source. Runtime name: `ffmpeg`. |
| whisper-cpp | `nix/whisper-cpp.nix`: nixpkgs `whisper-cpp` override at tag **1.8.4**, Vulkan on Linux (Metal on darwin in the separate macOS effort), `withSDL=false`, `withFFmpegSupport=false` | Pinned asset `whisperCpp.x86_64-linux`: url `https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-whisper-cpp-v1/whisper-cpp-1.8.4-x86_64-linux.tar.zst`, sha256 `33329560d092342ebdf61437413410f6e1f4ab769f6b91164f1f06038a79f1cf`, storePath `/nix/store/n2vf0kiskxmm9mfrs5mcla5q1k7zqpmi-whisper-cpp-1.8.4`, references `["/nix/store/10dxp0qxqxxsyiljrh2kp0xqhz6arhcx-bash-5.3p15", "/nix/store/h4wfwic161kxrr74jlzla5lsm28hgary-glibc-2.44-25", "/nix/store/j7qx4s4mr17j1wqgvqdzj33lmrnzb387-gcc-16.2.0-lib", "/nix/store/l60xx259knfbgfr7wzwsw7zg9ajmv3sn-wget-1.25.0", "/nix/store/vjdqsn0bz2llabfqa746zfgk4kmswba5-vulkan-loader-1.4.357.0"]`. Runtime name: `whisper-cli`. The version must stay 1.8.4 (flags, JSON and progress format). |
| whisper model | `nix/models.nix` fixed-output fetch | `https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-models-v1/ggml-large-v3-turbo-q5_0.bin`, sha256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`; `STT_WHISPER_MODEL`. |
| speaker model | `nix/models.nix` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, sha256 `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`; `STT_SPEAKER_MODEL`. |
| test whisper model | `nix/models.nix` | `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin`, sha256 `921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f`. |
| setsid/coreutils | nixpkgs `util-linux`, `coreutils` on PATH | Process groups; the app runs `setsid` directly. |
| SQLite | embedded in the app; a Rust rewrite may use any SQLite with WAL and foreign keys | Database file `stt.db`. |

The packaged wrapper (`packages.default` in `flake.nix`) defines the runtime contract: `PATH`
with this package's `whisper-cpp`, `ffmpeg`, `util-linux` and `coreutils` first; `STT_DATA_DIR`
defaulting to `${XDG_DATA_HOME:-$HOME/.local/share}/stt-app` (created); `STT_STATIC_DIR`; model
paths via `STT_WHISPER_MODEL`/`STT_SPEAKER_MODEL` (set-default, a user value wins). A Rust binary
can be wrapped the same way.

The prebuilt-artifact mechanism is `nix/prebuilt-artifact.nix` (fetch by url+sha256, unpack,
rewrite the embedded build path in place, scan for stray store paths, pin the references,
evaluation-time stale-pin gate), with pins in `nix/desktop-artifacts.nix` per artifact and
system and the asset printer in `nix/release-asset.nix`. Assets are GitHub Releases of this
repository, uploaded by the human; agents never publish. A system without a pin builds from
source. `versions.json` mirrors the tool/model pins for the Gradle bootstrap (`ffmpeg` 8.1,
`whisperCpp` 1.8.4, models, fixtures). The `mesaVulkan` pin in `nix/desktop-artifacts.nix` is the
desktop bundle's artifact, not a backend dependency.

Development tools (`devenv.nix`) include JDK/Gradle/node/pnpm/kotlin/playwright/sqlite/curl/jq/
util-linux/vulkan-tools plus the whisper-cpp, ffmpeg and models above; a Rust rewrite needs only
the runtime dependencies above (the shell also exports the `TEST_*` fixture paths).

## 17. Platform particularities

The current backend supports **Linux x86_64 only** (packaging, CI and the artifacts above).
macOS and Windows are separate, unmerged efforts; the notes below are what a cross-platform
rewrite must handle.

**Linux (current target).** `setsid`/util-linux for one process group per run; POSIX signals
SIGSTOP 19, SIGCONT 18, SIGTERM 15, SIGKILL 9; group signaling by negated pid; stdin
`/dev/null`; `PATH` executable resolution; XDG data directory; the Vulkan loader for whisper-cpp
and the static musl ffmpeg; SQLite WAL on a local filesystem. The backend itself never reads
`/proc` (only the e2e harness does).

**macOS (not in current main).** No `setsid`: a run is the direct child, so pause/resume/kill
target one pid and a forked descendant can escape (documented orphan risk). SIGSTOP is 17 and
SIGCONT 19. The pinned upstream whisper-cli prebuilds are CoreML (arm64) or have no GPU backend
(x64); a Nix-built whisper-cpp with Metal is the GPU path. ffmpeg comes from Nix/Homebrew (the
Linux audio-only artifact is Linux-shaped). Per-user paths are `~/Library/Application
Support/stt-app` (data) and `~/Library/Caches/stt-app/models`; there is no `/proc` (use `ps`);
GUI-launched processes get a minimal `PATH`; x86_64-darwin is unsupported by the pinned nixpkgs.

**Windows (not in current main).** No POSIX signals and no `setsid`: pausing a whisper-cli run
needs thread suspension (`NtSuspendProcess`) or a cooperative pause gate; killing a tree needs
Job Objects; executable resolution appends `.exe`; stdin is `NUL`; child output has CRLF (strip
`\r` before parsing); per-user paths are `%APPDATA%`/`%LOCALAPPDATA%`; SQLite locking differs;
Windows builds of whisper-cli and ffmpeg are required; `/proc` is absent.

**Cross-cutting.** Timestamps are epoch milliseconds UTC; formatting is locale-independent
(`Locale.ROOT`); the extension check is case-insensitive; filenames are arbitrary Unicode; the
upload limit is bytes; `STT_HOST` defaults to `0.0.0.0` (the desktop sets loopback); the data
directory holds the database and belongs on a local filesystem (WAL); child processes must be
reaped and their pipes drained on every path.

## 18. Not part of this reimplementation

The frontend, the Electron desktop app, Docker/Nix packaging and the e2e harness are not
implemented here, and the migration history is outside this spec. The current runtime dependency
and artifact contract (section 16) and the wrapper environment are **normative input** — the
rewrite must consume the same binaries, models and variables — but changing the packaging is out
of scope. The Rust backend only has to satisfy the HTTP/SSE contract, the database model and the
processing behavior above; it can serve the already-built frontend and run beside the existing
tooling.
