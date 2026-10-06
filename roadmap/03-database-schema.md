# 03 — Database schema

Status: draft
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done)

## Goal

Define the SQLite schema for tasks, uploaded audio, chunks, transcript segments and queue state, with migrations and suspend repositories that the engine and API build on.

## Already decided

- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): chunk plan and per-chunk segments are persisted so tasks resume from the last finished chunk.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): uploaded audio lives in the DB and is streamed to temp files.
- [ADR 0013 — Feature packages with Ktor DI](../docs/decisions/0013-backend-feature-packages-kotlin-di.md): this item is the first to wire the Ktor DI plugin (`ktor-server-di`); the database connection is the first registered service (decided in item 02).
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): Exposed DSL, Flyway versioned SQL migrations, Kotest.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): default value for `tasks.model`.

## Proposal (pending approval)

- SQLite file `$STT_DATA_DIR/stt.db`; WAL journal mode; foreign keys on.
- Flyway migration `V1__init.sql` with this draft schema:
  - `tasks(id, original_filename, content_type, duration_ms NULL, status CHECK IN ('QUEUED','RUNNING','PAUSED','COMPLETED','FAILED','CANCELED'), phase NULL CHECK IN ('CHUNKING','TRANSCRIBING','DIARIZING'), queue_position NULL, language DEFAULT 'en', speakers_mode CHECK IN ('auto','off','fixed'), speakers_count NULL, max_speakers DEFAULT 8, model, progress REAL DEFAULT 0, error NULL, created_at, started_at NULL, finished_at NULL)`
  - `audio(task_id PK FK → tasks ON DELETE CASCADE, data BLOB, size_bytes, sha256)` — separate table so task listings never load blobs.
  - `chunks(task_id, idx, start_ms, end_ms, status CHECK IN ('PENDING','DONE'), PK(task_id, idx))`
  - `segments(id, task_id, chunk_idx, seq, start_ms, end_ms, text, speaker NULL)`
  - `queue_state(id = 1 CHECK, paused INTEGER)` — single row.
- Exposed table objects plus repositories exposing `suspend` functions that run on `Dispatchers.IO`.
- Audio is written and read as streams (never fully materialized in memory), so it can be piped to a temp file for ffmpeg/whisper-cli.
- Transcript outputs are rendered on demand from `segments` (item 10), not stored.

## Open questions for the user

- Keep or delete the audio BLOB after a task completes? (History playback in item 15 needs it.)
- Store generated outputs (txt/srt/vtt/json) or render on demand? Proposal: render on demand from `segments`.

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Migrations apply cleanly to an empty database
- [ ] The database opens in WAL mode with foreign keys enforced
- [ ] A task saved through the repository reads back with identical fields
- [ ] An invalid `status`, `phase` or `speakers_mode` value is rejected by the database
- [ ] Chunks and segments saved for a task read back in `idx` / `seq` order
- [ ] Deleting a task cascades to its audio
- [ ] Audio is written and read back as a stream without loading the whole blob into memory, and its stored `sha256`/`size_bytes` match the content
- [ ] Listing tasks does not read audio data
- [ ] The queue paused flag round-trips and only one `queue_state` row can exist

## Acceptance criteria

- `V1__init.sql` creates all five tables on an empty DB; Flyway reports it applied.
- `PRAGMA journal_mode` returns `wal` and `PRAGMA foreign_keys` returns `1` on connections opened by the app.
- Repository functions are `suspend` and do their JDBC work on `Dispatchers.IO`.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
