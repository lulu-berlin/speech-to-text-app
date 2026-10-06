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

- SQLite file `$STT_DATA_DIR/stt.db`; WAL journal mode; foreign keys on; `journal_size_limit` 64 MiB on the writer, so the WAL shrinks back after a large upload (review round 2). The app opens the database at startup and runs Flyway migrations on every open; `STT_DATA_DIR` unset or empty fails startup naming the variable, a missing directory is created, and an uncreatable/unwritable path fails startup naming the variable ([ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)). Opening also fails naming `STT_DATA_DIR` when WAL cannot be enabled (the connection is closed, never leaked). `$STT_DATA_DIR/tmp` holds upload spool files; stale ones are removed when the database opens.
- Flyway migration `V1__init.sql` creates:
  - `tasks(id INTEGER PK, original_filename, content_type, duration_ms NULL, status CHECK IN ('QUEUED','RUNNING','PAUSED','COMPLETED','FAILED','CANCELED'), phase NULL CHECK IN ('CHUNKING','TRANSCRIBING','DIARIZING'), queue_position NULL, language DEFAULT 'en', speakers_mode CHECK IN ('auto','off','fixed'), speakers_count NULL, max_speakers DEFAULT 8, model, progress REAL DEFAULT 0, error NULL, created_at, started_at NULL, finished_at NULL)` — timestamps as INTEGER epoch millis UTC
  - `audio(task_id INTEGER PK, FK → tasks ON DELETE CASCADE, size_bytes, sha256 TEXT NOT NULL UNIQUE)` — one metadata row per task, no payload; the unique `sha256` enforces **at most one task per audio content** (the same bytes uploaded again, under any filename, is a duplicate: the second insert fails and its task creation rolls back)
  - `audio_parts(task_id, part_no, part BLOB, PK(task_id, part_no), FK → tasks ON DELETE CASCADE)` — audio payload as fixed 1 MiB pages ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md))
  - `chunks(task_id, idx, start_ms, end_ms, status CHECK IN ('PENDING','DONE'), PK(task_id, idx), FK → tasks ON DELETE CASCADE)`
  - `segments(id INTEGER PK, task_id, chunk_idx, seq, start_ms, end_ms, text, speaker NULL, UNIQUE(task_id, chunk_idx, seq), FK → tasks ON DELETE CASCADE)` — the unique key rejects a chunk's segments stored twice (ADR 0004 resume) and indexes `findByTask` and the delete cascade (added in review round 1, edited into `V1__init.sql` while unmerged)
  - `queue_state(id = 1 CHECK, paused INTEGER CHECK IN (0, 1), PK(id))` — single row
- Exposed table objects plus repositories in `stt/db` exposing `suspend` functions that run their JDBC work on `Dispatchers.IO`: `TaskRepository`, `AudioRepository`, `ChunkRepository`, `SegmentRepository`, `QueueStateRepository`, all backed by the `Database` service.
- `Database` is the first Ktor DI service, registered in the application module ([ADR 0013](../docs/decisions/0013-backend-feature-packages-kotlin-di.md), [ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)) via `dependencies { provide<Database> {…}; require(…) }`: `Application.dependencies` installs the DI plugin itself (an explicit `install(DI)` would throw once a test has provided an override first), and the requirement makes DI open the database when modules finish loading, i.e. at boot; DI closes it on application stop. Repositories stay plain constructor-injected until route/engine consumers arrive (item 12).
- Connection model ([ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)): `Database` owns **one writer connection**; write transactions take a suspending lock, so they run one at a time and never interleave. **Every read transaction** (`list`, `find`, `findByTask`, `metadata`, `copyTo`, `isPaused`) opens its own fresh read-only connection, closed when the transaction ends; WAL lets reads run alongside the writer, so a slow write or a stalled `copyTo` blocks nobody else.
- Audio is written from and read back to streams in page-sized windows — never the whole payload in memory ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md)). Uploads (`createWithAudio`, `AudioRepository.write`) first spool the source into a temp file under `$STT_DATA_DIR/tmp`, computing `size_bytes` and `sha256` on the way, before taking the write lock; then one short transaction inserts the task row (for `createWithAudio`), the `audio` metadata row — a duplicate `sha256` is rejected here by the UNIQUE constraint, before any page is written — and the pages read from the spool file. The temp file is deleted after success and after failure. Reads stream pages in `part_no` order to a temp file for ffmpeg/whisper-cli.
- Audio is kept after completion (history playback in item 15, re-processing); deletion is a future explicit feature, never a completion side effect ([ADR 0018](../docs/decisions/0018-audio-payload-paged-blobs.md)).
- Transcript outputs (txt/srt/vtt/json) are rendered on demand from `segments` (item 10), not stored.
- The packaged `bin/speech-to-text-app` (flake) refuses to start when `STT_DATA_DIR` is unset or empty: one line on stderr naming the variable, non-zero exit; no default value ([ADR 0017](../docs/decisions/0017-database-location-and-connection-contract.md)).

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
| 9 | Segment uniqueness (review round 1) | `UNIQUE (task_id, chunk_idx, seq)` in `V1__init.sql` (edited while unmerged); also indexes `findByTask` and the delete cascade | spec Proposal |
| 10 | Packaged app without `STT_DATA_DIR` (review round 1) | Wrapper check in `flake.nix`: one stderr line naming the variable, non-zero exit; no default | ADR 0017 |
| 11 | Connection model (review round 1) | One writer connection behind the suspending lock; a fresh read-only connection per read transaction (WAL); no new dependency | ADR 0017 |
| 12 | Upload write path (review round 1) | Spool to `$STT_DATA_DIR/tmp` (size + sha256 on the way) before the write lock; one short transaction inserts task, metadata (duplicate rejected by UNIQUE before any page), pages; temp file deleted on success and failure; stale temp files removed on open | ADR 0018 |
| 13 | Migrations and foreign keys (review round 1) | Flyway migrates on its own connection with foreign keys deliberately off (SQLite's table-rebuild procedure requires it) | ADR 0017 |
| 14 | WAL cannot be enabled (review round 1) | Open fails naming `STT_DATA_DIR`; the connection is closed on any PRAGMA failure; a `journal_mode` result other than `wal` also fails (defensive, untested: unreachable with a file URL) | ADR 0017 |
| 15 | WAL size after a large upload (review round 2) | `PRAGMA journal_size_limit = 67108864` (64 MiB) on the writer; the WAL is cut back to at most the limit when the first write after a completed checkpoint commits; peak disk use of an upload is about three times its size (spool + WAL + pages) | ADR 0017, ADR 0018 |

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
- [x] Concurrent writes do not interleave their transactions on the writer connection (discovered; setup changed in review round 1: the writer transaction is held open directly and a lock spy proves the second write waits)
- [x] Segments whose `seq` restarts in every chunk read back in (`chunk_idx`, `seq`) order (discovered)
- [x] Creating a task whose audio duplicates existing content fails and leaves no task, pages, or metadata behind (discovered)
- [x] An audio write whose source fails midway leaves no pages or metadata (discovered)
- [x] A fresh database reports the queue as not paused (discovered)
- [x] A data directory that cannot be created or opened fails `Database.open` with an error naming `STT_DATA_DIR` (discovered)
- [x] A second segment with the same task, `chunk_idx` and `seq` is rejected by the database (discovered)
- [x] A read completes while a write transaction is held open (discovered)
- [x] A write completes while a `copyTo` is stalled on its target (discovered)
- [x] A task creation whose upload source stalls does not block other writes; when the source then fails, no task, pages, metadata, or temp file remain (discovered)
- [x] A duplicate upload is rejected and leaves no temp file (discovered)
- [x] Stale files in `$STT_DATA_DIR/tmp` are removed when the database opens (discovered)
- [x] Opening fails naming `STT_DATA_DIR` when WAL cannot be enabled (discovered)
- [x] Stopping the application closes the Database provided by DI (discovered)
- [x] The packaged app started with `STT_DATA_DIR` unset or empty exits non-zero with a one-line message naming `STT_DATA_DIR`; with it set, it starts and serves `/api/health` (discovered; manual, user-verified: red on the current build, green after `nix build .`)
- [x] A migration that fails (e.g. an existing `stt.db` whose applied V1 checksum no longer matches) fails opening with an error naming `STT_DATA_DIR` (discovered)
- [ ] After a write larger than the WAL size limit, the WAL file shrinks back to at most the limit (discovered; review round 2: limit 16 KiB via an internal `Database.open` seam, a 1 MiB + 1 byte audio write, `PRAGMA wal_checkpoint(PASSIVE)` on the writer, then a one-page write; the `-wal` file ends at most 16 KiB)

## Acceptance criteria

- `V1__init.sql` creates all six tables on an empty DB; Flyway reports it applied; reopening an existing DB applies nothing and preserves data.
- `PRAGMA journal_mode` returns `wal` and `PRAGMA foreign_keys` returns `1` on the writer connection; read transactions run on fresh read-only connections alongside the writer.
- Repository functions are `suspend` and do their JDBC work on `Dispatchers.IO`; audio payload moves in 1 MiB page windows (constant memory), never as a whole blob; uploads are spooled to `$STT_DATA_DIR/tmp` before the write lock and leave no temp file behind.
- All test-list items ticked and green; user approved the diff.
- New Maven dependencies (`ktor-server-di`, Exposed, Flyway, `sqlite-jdbc`) are integrated by the user: `deps.json` regenerated and `nix build .` green (cannot be verified inside the agent sandbox).

## Approval log

- 2026-10-06: consultation decisions 1–7 approved (decision menu): keep audio after completion; render outputs on demand; `chunks`/`segments` tables and repositories included in this item; paged 1 MiB audio storage; `STT_DATA_DIR` fail-fast with auto-created directory; Database-only Ktor DI wiring; INTEGER epoch-millis timestamps. Test list of 13 items approved. ADRs 0017–0019 approved as written. Driver capability probe (xerial `sqlite-jdbc` 3.49.1.0, run 2026-10-06 in the agent sandbox) recorded in ADR 0018: no incremental BLOB I/O, no binary streams, no `readfile`/`writefile`.
- 2026-10-06: post-approval delta (user): **at most one task per audio content** — `audio.sha256` becomes `UNIQUE`; duplicate content is rejected by the database and its task creation rolls back (task + audio metadata row written in one transaction); strict rule, so a FAILED/CANCELED task also blocks re-scheduling the same file until an explicit retry feature exists. ADR 0018 amended; test item 14 added; spec text and decisions table updated. Approved as presented.
- 2026-10-06: decision 6 wording amended (user): `dependencies {}` auto-installs DI instead of an explicit `install(DI)` (which would conflict with test overrides registered first), plus `require` so the database opens at boot (ADR 0017). Intent unchanged.
- 2026-10-06: user verified `nix build .` green inside the worktree (with the branch's `backend/deps.json`): the new-dependency acceptance criterion is met.
- 2026-10-06: review round 1, changes requested: `segments` gets `UNIQUE (task_id, chunk_idx, seq)` in `V1__init.sql`; the packaged wrapper fails fast without `STT_DATA_DIR`; connection redesign (one writer behind the lock, a fresh read-only connection per read) and spooled uploads with temp-file cleanup (ADRs 0017/0018 amended); duplicates rejected by inserting the `audio` row before any page; migrations run with foreign keys deliberately off (ADR 0017); open fails naming `STT_DATA_DIR` when WAL cannot be enabled, plus an untested defensive `journal_mode` result check; `Database.connection` KDoc corrected (test-only by convention); test robustness: lock spy for the interleaving test (item 70 setup changed), spool-lag assertion for write-side constant memory, DI-close test, override Database closed. Nine discovered test items added. Approved as presented.
- 2026-10-06 (review round 1, stale-temp-file checkpoint): a non-empty directory inside `$STT_DATA_DIR/tmp` makes opening fail with an error naming `STT_DATA_DIR` (only files are removed; the app never creates directories there). Accepted by the user as fail-fast behavior (ADR 0017); no test item added.
- 2026-10-06 (review round 1, DI-close checkpoint): Ktor DI also closes an override `Database` on application stop (probed; `DatabaseDependencySpec` now closes it explicitly with `use {}`, user choice). Discovered: a failing Flyway migration rethrows Flyway's raw error without naming `STT_DATA_DIR` (an `stt.db` created from this branch before the `V1__init.sql` edit hits it); test item added by the user.
- 2026-10-06 (review round 1, packaged-app item): user verified red on the previous build (uncaught `IllegalArgumentException` stack trace, exit 1, for unset and empty `STT_DATA_DIR`) and green after `nix build .` at `d69133d` (one stderr line naming `STT_DATA_DIR`, exit 1, for unset and empty; with it set, Flyway applied V1 and `/api/health` returned `{"status":"ok"}`). The first green attempt failed in the Nix sandbox on a read-after-write race in the writer-lock test (fixed in `d69133d`).
- 2026-10-06: review round 2, changes requested: the WAL keeps the size of the largest upload because the writer connection lives as long as the app → `PRAGMA journal_size_limit = 67108864` (64 MiB, user choice) on the writer (ADR 0017 amended); an upload's peak disk use is about three times its size (spool + WAL + pages) and its bytes are written three times (ADR 0018 amended); test item added with the user's setup (explicit PASSIVE checkpoint, limit 16 KiB via an internal `Database.open` seam); missing final newlines added to ADRs 0017–0019 and this file. Approved as presented.
