# 03 — Database schema

Status: in-progress
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done)

## Goal

Define the SQLite schema for tasks, uploaded audio, chunks, transcript segments and queue state, with migrations and suspend repositories that the engine and API build on.

## Already decided

- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): chunk plan and per-chunk segments are persisted so tasks resume from the last finished chunk.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): uploaded audio lives in the DB and is streamed to temp files.
- [ADR 0013 — Feature packages with Ktor DI](../docs/decisions/0013-backend-feature-packages-kotlin-di.md): this item is the first to wire the Ktor DI plugin (`ktor-server-di`); the database connection is the first registered service (decided in item 02).
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): Exposed DSL, Flyway versioned SQL migrations, Kotest.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): default value for `tasks.model`.

## Proposal (approved 2026-10-06)

- SQLite file `$STT_DATA_DIR/stt.db`; WAL journal mode; foreign keys on. The app opens the database at startup and runs Flyway migrations on every open; `STT_DATA_DIR` unset or empty fails startup naming the variable, a missing directory is created, and an uncreatable/unwritable path fails startup naming the variable ([ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)).
- Flyway migration `V1__init.sql` creates:
  - `tasks(id INTEGER PK, original_filename, content_type, duration_ms NULL, status CHECK IN ('QUEUED','RUNNING','PAUSED','COMPLETED','FAILED','CANCELED'), phase NULL CHECK IN ('CHUNKING','TRANSCRIBING','DIARIZING'), queue_position NULL, language DEFAULT 'en', speakers_mode CHECK IN ('auto','off','fixed'), speakers_count NULL, max_speakers DEFAULT 8, model, progress REAL DEFAULT 0, error NULL, created_at, started_at NULL, finished_at NULL)` — timestamps as INTEGER epoch millis UTC
  - `audio(task_id INTEGER PK, FK → tasks ON DELETE CASCADE, size_bytes, sha256 TEXT NOT NULL UNIQUE)` — one metadata row per task, no payload; the unique `sha256` enforces **at most one task per audio content** (the same bytes uploaded again, under any filename, is a duplicate: the second insert fails and its task creation rolls back)
  - `audio_parts(task_id, part_no, part BLOB, PK(task_id, part_no), FK → tasks ON DELETE CASCADE)` — audio payload as fixed 1 MiB pages ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md))
  - `chunks(task_id, idx, start_ms, end_ms, status CHECK IN ('PENDING','DONE'), PK(task_id, idx), FK → tasks ON DELETE CASCADE)`
  - `segments(id INTEGER PK, task_id, chunk_idx, seq, start_ms, end_ms, text, speaker NULL, FK → tasks ON DELETE CASCADE)`
  - `queue_state(id = 1 CHECK, paused INTEGER CHECK IN (0, 1), PK(id))` — single row
- Exposed table objects plus repositories in `stt/db` exposing `suspend` functions that run their JDBC work on `Dispatchers.IO`: `TaskRepository`, `AudioRepository`, `ChunkRepository`, `SegmentRepository`, `QueueStateRepository`, all backed by the `Database` service.
- `Database` owns the JDBC `Connection` and is the first Ktor DI service, registered in the application module ([ADR 0013](../docs/decisions/0013-backend-feature-packages-kotlin-di.md), [ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)) via `dependencies { provide<Database> {…}; require(…) }`: `Application.dependencies` installs the DI plugin itself (an explicit `install(DI)` would throw once a test has provided an override first), and the requirement makes DI open the database when modules finish loading, i.e. at boot; DI closes it on application stop. Repositories stay plain constructor-injected until route/engine consumers arrive (item 12).
- Audio is written from and read back to streams in page-sized windows — never the whole payload in memory: uploads stream in as `audio_parts` rows inside one transaction while `size_bytes` and `sha256` are computed on the fly; reads stream pages in `part_no` order to a temp file for ffmpeg/whisper-cli ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md)).
- Audio is kept after completion (history playback in item 15, re-processing); deletion is a future explicit feature, never a completion side effect ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md)).
- Transcript outputs (txt/srt/vtt/json) are rendered on demand from `segments` (item 10), not stored.

## Open questions (answered 2026-10-06)

- Keep or delete the audio BLOB after a task completes? → **Keep** (history playback, item 15; ADR 0005 made the DB the only copy). Recorded in ADR 0018.
- Store generated outputs (txt/srt/vtt/json) or render on demand? → **Render on demand** from `segments` (item 10 owns rendering).

## Decisions (consultation 2026-10-06)

| # | Question | Decision | Recorded in |
| -- | -------- | -------- | ----------- |
| 1 | Keep or delete the audio BLOB after completion | Keep; deletion becomes an explicit feature | ADR 0018 |
| 2 | Store generated outputs or render on demand | Render on demand from `segments` (item 10) | spec Proposal |
| 3 | Do the `chunks`/`segments` tables and repositories belong here? | Yes: the full schema lands once in `V1__init.sql`; later items extend only via migrations | ADR 0019 |
| 4 | BLOB streaming mechanics | Paged storage: `audio` metadata row + 1 MiB `audio_parts` pages; constant-memory writes/reads; `size_bytes`/`sha256` computed on the write stream (driver cannot stream single-row BLOBs, probed 2026-10-06) | ADR 0018 |
| 5 | `STT_DATA_DIR` unset/empty | Fail fast naming the variable (consistent with the config rules); missing directory auto-created; DB at `$STT_DATA_DIR/stt.db`; connection opens at startup with WAL + FK | ADR 0017 |
| 6 | Ktor DI introduction | `dependencies { provide<Database> {…}; require(…) }` in the application module (auto-installs DI; required, so opened at boot); repositories plain-constructed in tests until item 12 consumers | spec Proposal, ADR 0013 conventions |
| 7 | Timestamp storage | INTEGER epoch millis UTC | spec Proposal |
| 8 | At most one task per audio content | `audio.sha256` is `UNIQUE`; task + its audio metadata row are written in one transaction, so a duplicate content is rejected and its task creation rolls back; strict rule — a FAILED/CANCELED task also blocks re-scheduling the same file until an explicit retry feature exists | ADR 0018, spec Proposal |

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-06. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] Migrations apply cleanly to an empty database; opening an existing database applies nothing and preserves its rows
- [x] The database opens in WAL mode with foreign keys enforced
- [x] A task saved through the repository reads back with identical fields
- [x] An invalid `status`, `phase` or `speakers_mode` value is rejected by the database
- [x] Chunks and segments saved for a task read back in `idx` / `seq` order
- [x] Deleting a task cascades to its audio, audio pages, chunks, and segments
- [x] Audio larger than one page is written from and read back to streams without holding the whole payload in memory, byte-for-byte identical, and its stored `size_bytes`/`sha256` match independently derived values
- [x] Listing tasks does not read audio data
- [x] The queue paused flag round-trips and only one `queue_state` row can exist
- [x] `STT_DATA_DIR` unset or empty → config load fails with an error naming `STT_DATA_DIR`
- [x] Opening the database creates the data directory when missing and creates `stt.db`
- [x] The application module registers the Database via Ktor DI; an application loads with it and a `testApplication` may override the provider before services load
- [x] Foreign keys reject `chunks`/`segments`/`audio`/`audio_parts` rows referencing a missing task
- [x] The database rejects a second audio row with the same `sha256` (at most one task per audio content)
- [x] Concurrent repository calls do not interleave their transactions on the shared connection (discovered)
- [x] Segments whose `seq` restarts in every chunk read back in (`chunk_idx`, `seq`) order (discovered)
- [x] Creating a task whose audio duplicates existing content fails and leaves no task, pages, or metadata behind (discovered)
- [x] An audio write whose source fails midway leaves no pages or metadata (discovered)
- [x] A fresh database reports the queue as not paused (discovered)
- [x] A data directory that cannot be created or opened fails `Database.open` with an error naming `STT_DATA_DIR` (discovered)

## Acceptance criteria

- `V1__init.sql` creates all six tables on an empty DB; Flyway reports it applied; reopening an existing DB applies nothing and preserves data.
- `PRAGMA journal_mode` returns `wal` and `PRAGMA foreign_keys` returns `1` on connections opened by the app.
- Repository functions are `suspend` and do their JDBC work on `Dispatchers.IO`; audio payload moves in 1 MiB page windows (constant memory), never as a whole blob.
- All test-list items ticked and green; user approved the diff.
- New Maven dependencies (`ktor-server-di`, Exposed, Flyway, `sqlite-jdbc`) are integrated by the user: `deps.json` regenerated and `nix build .` green (cannot be verified inside the agent sandbox).

## Approval log

- 2026-10-06: consultation decisions 1–7 approved (decision menu): keep audio after completion; render outputs on demand; `chunks`/`segments` tables and repositories included in this item; paged 1 MiB audio storage; `STT_DATA_DIR` fail-fast with auto-created directory; Database-only Ktor DI wiring; INTEGER epoch-millis timestamps. Test list of 13 items approved. ADRs 0017–0019 approved as written. Driver capability probe (xerial `sqlite-jdbc` 3.49.1.0, run 2026-10-06 in the agent sandbox) recorded in ADR 0018: no incremental BLOB I/O, no binary streams, no `readfile`/`writefile`.
- 2026-10-06: post-approval delta (user): **at most one task per audio content** — `audio.sha256` becomes `UNIQUE`; duplicate content is rejected by the database and its task creation rolls back (task + audio metadata row written in one transaction); strict rule, so a FAILED/CANCELED task also blocks re-scheduling the same file until an explicit retry feature exists. ADR 0018 amended; test item 14 added; spec text and decisions table updated. Approved as presented.
- 2026-10-06: decision 6 wording amended (user): `dependencies {}` auto-installs DI instead of an explicit `install(DI)` (which would conflict with test overrides registered first), plus `require` so the database opens at boot (ADR 0017). Intent unchanged.
- 2026-10-06: user verified `nix build .` green inside the worktree (with the branch's `backend/deps.json`): the new-dependency acceptance criterion is met.