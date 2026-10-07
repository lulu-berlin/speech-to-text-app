# 18 — Audio storage: originals in the work directory, chunks as FLAC

Status: in-progress
Depends on: [03](../docs/specs/database-schema.md) (done)

## Goal

Move the audio storage to ADR 0024: the original upload is no longer stored in the database but kept in the task's work directory until chunking, and the chunk FLACs are stored in the database. This item owns the `V2` migration and the repository changes; the engine (item 11) uses them.

## Already decided

- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole design; supersedes ADRs 0005 and 0018.
- [ADR 0019 — Schema ownership and evolution](../docs/decisions/0019-schema-ownership-and-evolution.md): `V1__init.sql` is never edited; the change arrives as a new versioned migration.
- [ADR 0017 — Database location and connection contract](../docs/decisions/0017-database-location-and-connection-contract.md): `$STT_DATA_DIR` layout, upload spools in `tmp/`, one writer behind a lock, fresh read-only connections for reads.
- [Item 03 spec](../docs/specs/database-schema.md): the current schema, repositories and their tests.

## Proposal (approved 2026-10-07)

- **Migration `V2`:** drop `audio_parts`; create `chunk_audio(task_id INTEGER NOT NULL, idx INTEGER NOT NULL, flac BLOB NOT NULL, PRIMARY KEY (task_id, idx), FOREIGN KEY (task_id, idx) REFERENCES chunks (task_id, idx) ON DELETE CASCADE)`. Matching Exposed table object; `AudioPartsTable` removed.
- **Work directories:** a small shared type (for example `TaskWorkDirectories`), built from `Config.dataDir` and registered in Ktor DI, is the one place that knows `$STT_DATA_DIR/work/<task-id>` and `…/source`. This item uses it for the upload hand-off; item 11 (engine, cleanup) and item 12 (delete) use it too. Its package is agreed with the user (consultation 2026-10-07).
- **Upload (`TaskRepository.createFromUpload`, renamed from `createWithAudio` because no audio bytes go into the database any more):** spool the source into `$STT_DATA_DIR/tmp` while computing `size_bytes` and `sha256` (unchanged); one short write transaction inserts the task row and the `audio` row (a duplicate `sha256` is rejected there, unchanged); after the commit, rename the spool to the task's `source` path, creating the work directory. A duplicate or any failure before the rename deletes the spool (consultation 2026-10-07).
- **`AudioRepository`:** `write` and `copyTo` (the original's pages) are removed; `metadata` stays.
- **Chunk audio:** one write transaction stores a task's chunk plan together with every chunk's FLAC, read from the files item 06 exported into the work directory, one file in memory at a time. This operation **replaces** the plan-only `ChunkRepository.insertAll`, so no code path can store a plan without its chunk audio (consultation 2026-10-07). A read operation writes one chunk's FLAC to an `OutputStream` (one BLOB, read on a fresh read-only connection). Plan queries (`ChunkRepository.findByTask`) never read `chunk_audio`.
- **Item 03 tests** whose subject was the original's pages (`audio_parts` round-trip, write-side memory, `copyTo` stall, the listing test that hides `audio_parts`) or the plan-only `insertAll` are replaced by tests of the new behavior; the agent lists them for the user at the first checkpoint, before changing any of them.

## Open questions (answered 2026-10-07)

- Where is the work directory path computed? → **A small shared type** built from `Config.dataDir` and registered in DI, used by items 18, 11 and 12.
- Does the plan-only `ChunkRepository.insertAll` stay? → **No: the plan-with-audio operation replaces it.**
- Should `TaskRepository.createWithAudio` keep its name? → **No: renamed to `createFromUpload`.**

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-07 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] `V2` applies to a database at `V1`: `audio_parts` is gone, `chunk_audio` exists, and existing tasks, `audio` rows and chunks are kept
- [ ] The work directory of task `n` is `$STT_DATA_DIR/work/n`, and its original is `$STT_DATA_DIR/work/n/source`
- [ ] Creating a task from an upload stores no audio bytes in the database and leaves the original byte-identical at `work/<task-id>/source`, with `size_bytes`/`sha256` derived independently
- [ ] A duplicate upload is rejected and leaves no task, no spool file and no work directory
- [ ] An upload whose source fails midway leaves no task, no spool file and no work directory
- [ ] Storing a plan with its chunk FLACs writes the plan and every FLAC in one transaction; a failure midway leaves neither
- [ ] A stored chunk FLAC reads back byte-identical
- [ ] Deleting a task removes its chunk audio (cascade through `chunks`)
- [ ] A `chunk_audio` row for a chunk that does not exist is rejected (foreign key)
- [ ] Reading the chunk plan does not read chunk audio

## Acceptance criteria

- `V1__init.sql` is unchanged; `V2` is the only schema change; a database at `V1` migrates without losing tasks, `audio` rows or chunk plans.
- No original upload bytes are written to the database; at most one chunk FLAC is held in memory at a time.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-07: consultation decisions approved: the work directory path lives in a small shared type built from `Config.dataDir` and registered in DI (used by items 18, 11, 12); the plan-with-audio operation replaces the plan-only `ChunkRepository.insertAll`; `TaskRepository.createWithAudio` is renamed `createFromUpload`. Test list of 10 items approved with the spec. No new ADR: these follow ADR 0024 and are recorded here.
