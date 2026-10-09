# 12 — HTTP API and SSE

Status: draft
Depends on: [10](10-output-formats.md), [11](11-processing-engine.md)

## Goal

Expose the queue and the transcription history over a REST API for all commands and a Server-Sent Events stream for live updates, so the frontend can upload files, control the queue and tasks, delete tasks, and read or download transcripts.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Ktor backend serving the frontend.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the upload is spooled, deduplicated by its `sha256`, and kept in the task's work directory until chunking; afterwards only the chunk FLACs exist, and playback joins them.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): route tests with Kotest.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): live updates via the Ktor SSE plugin; all commands via REST.
- [ADR 0028 — Speakers table; segments reference their speaker by `speaker_id`](../docs/decisions/0028-speakers-table-and-segment-speaker-id.md) (item 09, on branch `roadmap/09-diarization` until it merges): `speakers(task_id, speaker_id, speaker)` holds one row per speaker of a task, and segments reference it by `speaker_id`. A speaker is identified by its 0-based `speakerId`; `speaker` is only ever its name ("Speaker N" as stored by the app, renamable by a later feature).
- **This item's spec is the frontend's contract** (consultation 2026-10-09): once it is `spec-approved`, items 13–15 build against it with MSW mocks, in parallel with items 09–12. The final check against the running backend is item 21.

## Proposal (pending approval)

- **Upload.**
  - `POST /api/tasks`, multipart, with these fields:
    - `file`;
    - `language` (default `en`), validated against the language codes whisper-cli accepts, plus `auto`; anything else → `400 invalid-language`;
    - `speakers` = `auto|off|<n>`, where `1 <= n <= 20`;
    - `maxSpeakers` (default 8, `1 <= maxSpeakers <= 20`).
  - Only files whose extension is on the allow-list are accepted. The extension is checked case-insensitively, and the content type is ignored, because browsers often send generic types. The allow-list: `mp3 wav flac ogg oga opus m4a aac wma aif aiff amr webm mp4 m4v mov mkv mka 3gp`. Video files are accepted because ffmpeg extracts their audio track.
  - A file on the list that ffmpeg still cannot decode becomes a FAILED task with a readable error at CHUNKING (item 11).
  - There is no size limit. The file part is streamed (no full in-memory copy) into `TaskRepository.createFromUpload` (item 18): it is spooled with its `size_bytes`/`sha256` computed on the way, the task and its `audio` row are committed, and the original is moved to the task's work directory. No audio bytes go into the database. A failed spool (e.g. disk full) leaves no task.
  - The task is created QUEUED at the end of the queue. Returns `201` with the task.
  - A file whose bytes duplicate an existing task is rejected with `409` `duplicate-audio`, and the body names the existing task's id (see Contract).
- **Tasks.** `GET /api/tasks` (list without audio), `GET /api/tasks/{id}`.
- **Task commands.** `POST /api/tasks/{id}/pause`, `POST /api/tasks/{id}/resume`, `POST /api/tasks/{id}/cancel` delegate to the engine (item 11). Illegal transitions per item 04 → `409`.
- **Delete.** `DELETE /api/tasks/{id}` works on any task.
  - A pending task (QUEUED, RUNNING, PAUSED) is first cancelled through the engine (its process is stopped and the cancel has finished), then deleted.
  - Deleting removes:
    - the task row, which cascades to its segments, chunks, chunk audio, and its `audio` row (so the same file can be uploaded again);
    - its work directory;
    - its cached joined FLAC.
  - `204`, or `404` for an unknown id. Emits `task-deleted`, plus `queue-updated` if the task was pending.
- **Queue.** `GET /api/queue` (paused flag + ordered task ids), `POST /api/queue/pause`, `POST /api/queue/resume`, `PUT /api/queue/order` with body `{ "taskIds": [...] }`: the full ordered list of non-terminal task ids. A different set → `409`.
- **Transcript download.** `GET /api/tasks/{id}/transcript?format=txt|srt|vtt|json`, rendered on demand by item 10's renderers, served as an attachment with the matching content type.
- **Turns for the transcript view.** `GET /api/tasks/{id}/turns` returns the task's speakers (id and stored name) and item 09's speaker turns, each with its segments, so turn merging exists only in Kotlin. Turns reference their speaker by `speakerId`, so a later rename changes one name and no turn. The JSON download stays the oracle format.
- **Audio.**
  - `GET /api/tasks/{id}/audio` joins the task's stored chunk FLACs in `idx` order with ffmpeg's concat demuxer (`-c copy`, no re-encode) into one FLAC, served as `audio/flac` (16 kHz mono, ADR 0024).
  - The joined file is built once into `$STT_DATA_DIR/tmp/<task-id>.flac`: written under a temporary name and renamed, one build per task at a time.
  - It is served with HTTP Range support, so the browser can seek and item 15 can offer click-to-seek.
  - The cache is removed when the task is deleted, and at startup (`tmp/` is emptied when the database opens).
  - A task without stored chunks (not chunked yet, or canceled or failed before chunking) → `404`.
- **Events.** `GET /api/events`, an SSE stream of the engine's `SharedFlow<Event>` (item 11).
  - On every connect the first event is `snapshot` (all tasks + queue); live events follow.
  - `task-updated` carries the full task, so applying any event twice is harmless and the client needs no refetch after reconnecting.
- **Errors.** Body `{ "error": "<code>", "message": "..." }`, with `taskId` added for `duplicate-audio` only. No stack traces in responses.
- JSON via kotlinx.serialization; route handlers stay thin and call the engine/repositories.

## Contract (proposed 2026-10-09, for approval)

The JSON shapes the frontend builds against.

- **Example files.** One example JSON file per response body, SSE event payload and error lives in [`docs/api/`](../docs/api/), created on `main` when this spec is approved.
  - Item 13's MSW handlers serve these files.
  - This item's route tests check that every real response has the example's structure: the same keys, value types and nullability.
  - A contract change edits the example file and this section in the same commit.
- **Task.**
  - Fields:
    - `id` (number)
    - `filename` (string)
    - `status` (`QUEUED|RUNNING|PAUSED|COMPLETED|FAILED|CANCELED`)
    - `phase` (`CHUNKING|TRANSCRIBING|DIARIZING|null`)
    - `language` (string)
    - `speakersMode` (`auto|off|fixed`)
    - `speakersCount` (number, or null unless `fixed`)
    - `maxSpeakers` (number)
    - `durationMs` (number, or null before chunking)
    - `sizeBytes` (number)
    - `transcriptionProgress` (0–1)
    - `diarizationProgress` (0–1, or null)
    - `error` (string or null)
    - `createdAt`, `startedAt`, `finishedAt` (epoch ms, or null)
  - `diarizationProgress` is `null` when diarization does not apply: speakers `off`, or the task finished without segments. Otherwise it is 0 until diarization starts.
  - While `phase = DIARIZING`, a value of 1 means clustering is running (item 11).
- **Task list** (`GET /api/tasks`): `{ "tasks": [Task] }`. Pending tasks come first, in queue order, then finished tasks, newest `finishedAt` first.
- **Queue:** `{ "paused": boolean, "taskIds": [number] }` (pending tasks in queue order).
- **Responses by route:**
  - upload → `201` Task;
  - pause/resume/cancel → `200` Task;
  - queue pause/resume/order → `200` Queue;
  - delete → `204` without a body.
- **Turns** (`GET /api/tasks/{id}/turns`): `{ "speakers": [{ "speakerId", "speaker" }], "turns": [{ "speakerId" | null, "startMs", "endMs", "text", "segments": [{ "startMs", "endMs", "text" }] }] }`.
  - `speakers` are the task's rows of the `speakers` table (ADR 0028), ordered by `speakerId`: `speakerId` the 0-based number, `speaker` the stored name (`"Speaker N"`, N = `speakerId` + 1, until a rename feature exists).
  - `turns` are item 09's `speaker_turns`: consecutive segments with the same `speakerId` form one turn. A turn names its speaker only by `speakerId`; the UI shows the name from `speakers`.
  - Only for COMPLETED tasks; any other status → `409 not-completed`.
  - With speakers `off`: `speakers` is empty, and there is one turn per segment with `speakerId: null`.
- **Events** (`event:` name → `data:` JSON):
  - `snapshot` → `{ "tasks": [Task], "queue": Queue }`
  - `task-updated` → Task
  - `task-progress` → `{ "id", "transcriptionProgress", "diarizationProgress" }`
  - `queue-updated` → Queue
  - `task-deleted` → `{ "id" }`
- **Error codes:**

  | Status | Codes |
  | ------ | ----- |
  | `400` | `missing-file`, `unsupported-format`, `invalid-language`, `invalid-speakers`, `invalid-max-speakers`, `invalid-transcript-format` |
  | `404` | `not-found` (unknown task), `no-audio` (task without stored chunks) |
  | `409` | `illegal-transition`, `reorder-mismatch`, `duplicate-audio` (with `taskId`), `not-completed` (turns of an unfinished task) |
  | `500` | `internal` |

## Open questions (answered 2026-10-09)

- Upload size limit? → **None**: uploads are streamed to disk; a failed spool leaves no task.
- Accepted formats? → **Extension allow-list, audio and video** (list above). The content type is ignored.
- Range support for `/audio`, caching? → **Range, with the joined FLAC cached in `$STT_DATA_DIR/tmp`** until the task is deleted or the app restarts.
- Delete endpoint? → **Yes, for any task**: a pending task is cancelled through the engine first. Deleting frees the `sha256`, so a file whose task FAILED or was CANCELED can be uploaded again.
- Initial state on `/api/events`? → **The server sends a `snapshot` first**; `task-updated` carries the full task.
- Add item 10 to `Depends on`? → **Yes.**
- Upper bound for `maxSpeakers` and a fixed `<n>`? → **20** for both (items 14's inputs use the same bound).
- Duplicate upload response? → **`409` `duplicate-audio` with `taskId`.**
- Where does the transcript view get turns? → **`GET /api/tasks/{id}/turns`.**
- Where do the contract examples live? → **`docs/api/`.**
- `language`? → **Validated against whisper-cli's language codes plus `auto`**; otherwise `400 invalid-language`.
- `/turns` for a task that is not COMPLETED? → **`409 not-completed`.**
- `/turns` with speakers `off`? → **One turn per segment, `speaker: null`.**

## Remaining open questions

- The contract above (field names, list order, status codes) as written?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Uploading a file creates a QUEUED task at the end of the queue, records its `size_bytes`/`sha256`, and keeps the original in the task's work directory (no audio bytes in the database)
- [ ] Uploading without a file, with an extension outside the allow-list, with a language whisper-cli does not know, or with an invalid `speakers` or `maxSpeakers` value (outside 1–20), is rejected with 400 and the matching error code
- [ ] Uploading the same bytes again is rejected with 409 `duplicate-audio` naming the existing task's id
- [ ] Listing tasks returns pending tasks in queue order, then finished tasks newest first, without audio data
- [ ] Requesting an unknown task returns 404 with an error body
- [ ] Pausing, resuming, and cancelling a task reach the engine and return the updated task
- [ ] An illegal task command (e.g. pausing a COMPLETED task) returns 409
- [ ] Deleting a finished task removes it with its segments, chunk audio, work directory and cached audio; the same file can then be uploaded again
- [ ] Deleting a pending task cancels it through the engine first, then removes it
- [ ] Reordering with the full set of non-terminal ids changes the queue order
- [ ] Reordering with a different set of ids returns 409 and leaves the order unchanged
- [ ] Pausing and resuming the queue is reflected in `GET /api/queue`
- [ ] Downloading a transcript in each format returns the rendered output with the right content type
- [ ] Downloading with an unknown format returns 400
- [ ] The turns endpoint returns the task's speakers with their stored names and item 09's turns (by `speakerId`) with their segments for a COMPLETED task; with speakers `off`, no speakers and one turn per segment with `speakerId` null; 409 `not-completed` for any other status
- [ ] Audio download returns one FLAC (`audio/flac`) joining the task's stored chunks in order; a task without stored chunks returns 404
- [ ] A Range request for the audio returns 206 with the requested bytes of the joined FLAC
- [ ] An SSE client first receives a `snapshot`, then `task-updated`, `task-progress`, `queue-updated` and `task-deleted` events as the engine emits them
- [ ] Every response and event payload has the structure of its `docs/api/` example

## Acceptance criteria

- Every route has a Ktor `testApplication` test, including its 404/409 paths.
- An SSE test receives the snapshot and then the events in emission order.
- Uploading a large file does not load it fully into memory (verified by streaming design and a test with a file larger than the configured buffer).
- Error responses always use the `{ "error", "message" }` body.
- Every response and event shape matches its `docs/api/` example.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: consultation answers approved (open questions above): no size limit; extension allow-list for audio and video; Range with a cached joined FLAC; DELETE for any task (a pending one is cancelled first); `snapshot` event first; item 10 added to `Depends on`; speaker bounds 1–20; `409 duplicate-audio` with `taskId`; `/turns` endpoint; contract examples in `docs/api/`. This item's spec becomes the frontend contract; the frontend items build against it with mocks, and the check against the running backend is item 21. Contract draft and remaining questions pending.
- 2026-10-09: further answers: `language` validated against whisper-cli's codes plus `auto` (`400 invalid-language`); `/turns` only for COMPLETED tasks (`409 not-completed`); speakers `off` → one turn per segment with `speaker: null`. Remaining: approval of the contract as written.
- 2026-10-09: contract adapted to item 09's speaker model (ADR 0028, user request): `/turns` returns `speakers` (`speakerId`, stored name `speaker`) and turns that reference their speaker by `speakerId` only; JSON naming follows ADR 0028 (`speakerId` = 0-based number, `speaker` = name). Remaining: approval of the contract as written.
