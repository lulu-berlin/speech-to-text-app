# 12 — HTTP API and SSE

Status: draft
Depends on: [11](11-processing-engine.md)

## Goal

Expose the queue and the transcription history over a REST API for all commands and a Server-Sent Events stream for live updates, so the frontend can upload files, control the queue and tasks, and read or download transcripts.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Ktor backend serving the frontend.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the upload is spooled, deduplicated by its `sha256`, and kept in the task's work directory until chunking; afterwards only the chunk FLACs exist, and playback joins them.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): route tests with Kotest.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): live updates via the Ktor SSE plugin; all commands via REST.

## Proposal (pending approval)

- **Upload.** `POST /api/tasks` multipart with fields `file`, `language` (default `en`), `speakers` = `auto|off|<n>` (n >= 1, as the oracle's `parse_speakers`), `maxSpeakers` (default 8). The file part is streamed (no full in-memory copy) into `TaskRepository.createWithAudio` (item 18): it is spooled with its `size_bytes`/`sha256` computed on the way, the task and its `audio` row are committed, and the original is moved to the task's work directory; no audio bytes go into the database. The task is created QUEUED at the end of the queue. Returns `201` with the task.
- **Tasks.** `GET /api/tasks` (list without audio), `GET /api/tasks/{id}`.
- **Task commands.** `POST /api/tasks/{id}/pause`, `POST /api/tasks/{id}/resume`, `POST /api/tasks/{id}/cancel` — delegate to the engine (item 11); illegal transitions per item 04 → `409`.
- **Queue.** `GET /api/queue` (paused flag + ordered task ids), `POST /api/queue/pause`, `POST /api/queue/resume`, `PUT /api/queue/order` with body `{ "taskIds": [...] }` — the full ordered list of non-terminal task ids; a different set → `409`.
- **Transcript download.** `GET /api/tasks/{id}/transcript?format=txt|srt|vtt|json`, rendered on demand by item 10's renderers, served as an attachment with the matching content type.
- **Audio.** `GET /api/tasks/{id}/audio` joins the task's stored chunk FLACs in `idx` order with ffmpeg's concat demuxer (`-c copy`, no re-encode) into one FLAC, served as `audio/flac` (16 kHz mono, ADR 0024). A task without stored chunks (not chunked yet, or canceled or failed before chunking) → `404`.
- **Events.** `GET /api/events` SSE stream of the engine's `SharedFlow<Event>` (item 11) with event names `task-updated`, `task-progress`, `queue-updated` and JSON payloads.
- **Errors.** Body `{ "error": "<code>", "message": "..." }`; `400` invalid input (unknown format, bad `speakers`, missing file), `404` unknown task, `409` illegal state transition or reorder set mismatch.
- JSON via kotlinx.serialization; route handlers stay thin and call the engine/repositories.

## Open questions for the user

- Upload size limit (per file and per request)?
- Accepted formats: anything ffmpeg can decode (oracle accepts any format), or an allow-list by content type/extension?
- HTTP Range support for `/audio` (needed for seeking in the browser's audio player), and is the joined FLAC cached (for example in `$STT_DATA_DIR/tmp`, which is emptied at startup) or rebuilt on every request?
- Delete endpoint for history entries (`DELETE /api/tasks/{id}`) — in scope? If added: deleting a task also deletes its work directory `$STT_DATA_DIR/work/<task-id>` at once, which holds the original of a task not yet chunked (ADR 0024).
- On connecting to `/api/events`, does the server send an initial snapshot, or does the client fetch `GET /api/tasks` + `GET /api/queue` after connecting?
- The transcript endpoint uses item 10's renderers, which `Depends on` does not list yet (item 11 does not depend on 10): add [10](10-output-formats.md) to `Depends on`?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Uploading a file creates a QUEUED task at the end of the queue, records its `size_bytes`/`sha256`, and keeps the original in the task's work directory (no audio bytes in the database)
- [ ] Uploading without a file, or with an invalid `speakers` value, is rejected with 400 and an error body
- [ ] Listing tasks returns all tasks in queue order without audio data
- [ ] Requesting an unknown task returns 404 with an error body
- [ ] Pausing, resuming, and cancelling a task reach the engine and return the updated task
- [ ] An illegal task command (e.g. pausing a COMPLETED task) returns 409
- [ ] Reordering with the full set of non-terminal ids changes the queue order
- [ ] Reordering with a different set of ids returns 409 and leaves the order unchanged
- [ ] Pausing and resuming the queue is reflected in `GET /api/queue`
- [ ] Downloading a transcript in each format returns the rendered output with the right content type
- [ ] Downloading with an unknown format returns 400
- [ ] Audio download returns one FLAC (`audio/flac`) joining the task's stored chunks in order; a task without stored chunks returns 404
- [ ] An SSE client receives `task-updated`, `task-progress`, and `queue-updated` events as the engine emits them

## Acceptance criteria

- Every route has a Ktor `testApplication` test, including its 404/409 paths.
- An SSE test receives events in emission order.
- Uploading a large file does not load it fully into memory (verified by streaming design and a test with a file larger than the configured buffer).
- Error responses always use the `{ "error", "message" }` body.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
