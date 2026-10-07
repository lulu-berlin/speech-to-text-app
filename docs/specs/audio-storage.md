# Audio storage: originals in the work directory, chunks as FLAC

Status: done (roadmap item 18, merged 2026-10-07)
Depends on: [03 — database schema](database-schema.md)

## Goal

Move the audio storage to ADR 0024: the original upload is no longer stored in the database but kept in the task's work directory until chunking, and the chunk FLACs are stored in the database. This item owns the `V2` migration and the repository changes; the engine (item 11) uses them.

## Decisions

- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole design; supersedes ADRs 0005 and 0018. This item implements it.
- [ADR 0019 — Schema ownership and evolution](../decisions/0019-schema-ownership-and-evolution.md): `V1__init.sql` is never edited; the change arrives as a new versioned migration.
- [ADR 0017 — Database location and connection contract](../decisions/0017-database-location-and-connection-contract.md): `$STT_DATA_DIR` layout, upload spools in `tmp/`, one writer behind a lock, fresh read-only connections for reads.
- [Item 03 spec](database-schema.md): the current schema, repositories and their tests.
- Amended in this item: [ADR 0023 — Persistence may import feature-owned value types](../decisions/0023-persistence-may-import-feature-value-types.md) (persistence may consume a feature's processing-result type when reading it inside one transaction keeps a multi-row write atomic and memory-bounded).

## Proposal (approved 2026-10-07)

- **Migration `V2`:** drop `audio_parts`; create `chunk_audio(task_id INTEGER NOT NULL, idx INTEGER NOT NULL, flac BLOB NOT NULL, PRIMARY KEY (task_id, idx), FOREIGN KEY (task_id, idx) REFERENCES chunks (task_id, idx) ON DELETE CASCADE)`. Matching Exposed table object; `AudioPartsTable` removed.
- **Work directories:** a small shared type (for example `TaskWorkDirectories`), built from `Config.dataDir` and registered in Ktor DI, is the one place that knows `$STT_DATA_DIR/work/<task-id>` and `…/source`. This item uses it for the upload hand-off; item 11 (engine, cleanup) and item 12 (delete) use it too. Its package is agreed with the user (consultation 2026-10-07).
- **Upload (`TaskRepository.createFromUpload`, renamed from `createWithAudio` because no audio bytes go into the database any more):** spool the source into `$STT_DATA_DIR/tmp` while computing `size_bytes` and `sha256` (unchanged); one short write transaction inserts the task row and the `audio` row (a duplicate `sha256` is rejected there, unchanged); after the commit, rename the spool to the task's `source` path, creating the work directory. A duplicate or any failure before the rename deletes the spool (consultation 2026-10-07). If creating the work directory or moving the spool to `source` fails after the commit, `createFromUpload` deletes the task again (its `audio` row goes with it, so a retry with the same bytes is no longer rejected as a duplicate `sha256`), removes the work directory best-effort, and rethrows the original exception (review round 1).
- **`AudioRepository`:** `write` and `copyTo` (the original's pages) are removed; `metadata` stays.
- **Chunk audio:** one write transaction stores a task's chunk plan together with every chunk's FLAC, read from the files item 06 exported into the work directory, one file in memory at a time. This operation **replaces** the plan-only `ChunkRepository.insertAll`, so no code path can store a plan without its chunk audio (consultation 2026-10-07). A read operation writes one chunk's FLAC to an `OutputStream` (one BLOB, read on a fresh read-only connection). Plan queries (`ChunkRepository.findByTask`) never read `chunk_audio`.
- **Item 03 tests** whose subject was the original's pages (`audio_parts` round-trip, write-side memory, `copyTo` stall, the listing test that hides `audio_parts`) or the plan-only `insertAll` are replaced by tests of the new behavior; the agent lists them for the user at the first checkpoint, before changing any of them.

## Open questions (answered 2026-10-07)

- Where is the work directory path computed? → **A small shared type** built from `Config.dataDir` and registered in DI, used by items 18, 11 and 12.
- Does the plan-only `ChunkRepository.insertAll` stay? → **No: the plan-with-audio operation replaces it.**
- Should `TaskRepository.createWithAudio` keep its name? → **No: renamed to `createFromUpload`.**

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-07 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] `V2` applies to a database at `V1`: `audio_parts` is gone, `chunk_audio` exists, and existing tasks, `audio` rows and chunks are kept
- [x] The work directory of task `n` is `$STT_DATA_DIR/work/n`, and its original is `$STT_DATA_DIR/work/n/source`
- [x] Creating a task from an upload stores no audio bytes in the database and leaves the original byte-identical at `work/<task-id>/source`, with `size_bytes`/`sha256` derived independently
- [x] A duplicate upload is rejected and leaves no task, no spool file and no work directory
- [x] An upload whose source fails midway leaves no task, no spool file and no work directory
- [x] Storing a plan with its chunk FLACs writes the plan and every FLAC in one transaction; a failure midway leaves neither
- [x] A stored chunk FLAC reads back byte-identical
- [x] Deleting a task removes its chunk audio (cascade through `chunks`)
- [x] A `chunk_audio` row for a chunk that does not exist is rejected (foreign key)
- [x] Reading the chunk plan does not read chunk audio
- [x] A chunk-FLAC read completes while a write transaction is held open (discovered; replaces item 03's `copyTo` stall test)
- [x] An upload whose original cannot be moved into the work directory leaves no task and no audio row, and re-uploading the same bytes afterwards succeeds (discovered, review round 1)

## Acceptance criteria

- `V1__init.sql` is unchanged; `V2` is the only schema change; a database at `V1` migrates without losing tasks, `audio` rows or chunk plans.
- No original upload bytes are written to the database; at most one chunk FLAC is held in memory at a time.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-07: consultation decisions approved: the work directory path lives in a small shared type built from `Config.dataDir` and registered in DI (used by items 18, 11, 12); the plan-with-audio operation replaces the plan-only `ChunkRepository.insertAll`; `TaskRepository.createWithAudio` is renamed `createFromUpload`. Test list of 10 items approved with the spec. No new ADR: these follow ADR 0024 and are recorded here.
- 2026-10-07 (workspace checkpoint 1): work-directory type is `stt.db.TaskWorkDirectories(dataDir)` with `workDir(taskId)` and `source(taskId)`, registered in Ktor DI. Cycle order 2 → 3 → 4 → 5 → 1 → 6 → 7 → 10 → 9 → 8 (V2 cannot precede the end of page writes). Item 03 test replacements approved: `AudioRepositorySpec` removed with `write`/`copyTo` in item 1's cycle, its byte-identity, `size_bytes`/`sha256` and spool-memory checks carried into item 3's test and its failing-source check into item 5's; `TaskRepositorySpec`'s two duplicate tests merged into item 4's test, the `audio_parts` checks dropped from the duplicate and cascade tests in item 3's cycle, the cascade setup moved to the plan-with-audio operation in item 6 and extended with `chunk_audio` for item 8, the listing test hides `chunk_audio` instead of `audio_parts` from item 1; `ConcurrencySpec`'s `copyTo` stall test removed in item 1 and replaced by a `(discovered)` chunk-FLAC read stall test after item 7, the upload-stall test loses its `audio_parts` check in item 1; `DatabaseSpec`'s first-open test expects `chunk_audio` and two migrations, its foreign-key test drops `audio_parts`, its WAL-limit test grows the WAL with a task whose `error` is 1 048 577 characters (from item 3); `ChunkRepositorySpec` setup moves to the plan-with-audio operation in item 6; `SegmentRepositorySpec` is unaffected.
- 2026-10-07 (workspace checkpoint after item 3): confirmed changes made in item 3's cycle: `ConcurrencySpec`'s `copyTo` stall test sets its pages up with `TaskRepository.insert` + `AudioRepository.write` until item 1 removes it (uploads no longer write pages); `GeneratedBytes`, `SpoolWatchingSource`, `sha256Hex` and `MIB` moved unchanged from `AudioRepositorySpec` into the shared `TestUploads.kt`; a test helper builds `TaskRepository` with the work directories of the test's data directory. Refactor approved: `withDatabase` hands its block the data directory, and the helper takes it instead of deriving it from `tmp/`.
- 2026-10-07 (workspace checkpoint after item 6): `ChunkRepository.storePlan(taskId, List<ExportedChunk>)` replaces `insertAll` (idx = position, every chunk PENDING). Confirmed: `ChunkRepositorySpec`'s ordering test loses its out-of-order setup (`storePlan` stores in idx order) and keeps idx order, the per-task filter and DONE/PENDING; DONE is set in the test through `db.writeTransaction { exec(…) }`, because raw SQL on `db.connection` after an Exposed transaction stays uncommitted (auto-commit is left off). The chunk FLAC read is `ChunkRepository.copyFlacTo(taskId, idx, target)`; a chunk without a stored FLAC throws `NoSuchElementException` naming the task and idx (not on the test list).
- 2026-10-07 (workspace checkpoint after item 7): the replacement for item 03's `copyTo` stall test is "A chunk-FLAC read completes while a write transaction is held open" (discovered), added as a `copyFlacTo` check to `ConcurrencySpec`'s "every repository read runs while the write is open" test. A stall on the read's target cannot catch a read routed through the writer, because `copyFlacTo` closes its connection before writing to the target; a read held behind the writer lock can.
- 2026-10-07 (review round 1): discovered test list item approved: "An upload whose original cannot be moved into the work directory leaves no task and no audio row, and re-uploading the same bytes afterwards succeeds". The user decided that a move failure after the commit deletes the task again (its `audio` row goes with it), removes the work directory best-effort, and rethrows the original exception so the caller (item 12) can report it. Caveat: if that cleanup itself fails, the failure is logged by the caller's usual error path and the original exception is not swallowed; when the cleanup transaction itself fails the task rows are not removed — corrected in review round 2, which suppresses the cleanup failure onto the original exception.
- 2026-10-07 (review round 1): ADR 0023 amended as decided (processing-result types like `ExportedChunk` may cross into `stt/db` when reading one file at a time inside the single transaction keeps the multi-row write atomic and memory-bounded), with the wording in the ADR.
- 2026-10-07 (review round 2): the rollback after a failed move now runs under `withContext(NonCancellable)`: both `db.writeTransaction` and `deleteWorkDir` are cancellation checkpoints, so a canceled caller would skip the rollback and leave a committed task without its original (a normal situation once item 12 exists — client disconnect). A cleanup-transaction failure is suppressed onto the original exception (`addSuppressed`). Defensive change; no test: reaching the canceled path needs a seam between the commit and a failing move, which the user chose not to add.
- 2026-10-07 (review round 2): the round-1 red was reproduced in an uncommitted mutation that removed the rollback's `db.writeTransaction { TasksTable.deleteWhere { … } }` line: `TaskRepositorySpec.Then: the task and its audio row are deleted, no work directory or spool remains, and re-uploading the same bytes succeeds FAILED` with `AssertionFailedError: expected:<0> but was:<1>` on the task row count. The mutation was restored before the fix.
- 2026-10-07 (review round 3): no new findings. Reviewer re-ran `gradle check` (76/76) and `gradle integrationTest` (29/29), and verified the round-2 rollback empirically in a scratch copy: a cancelled caller's `db.writeTransaction` does not run its block, while the same call inside `withContext(NonCancellable)` does. User approved the diff and chose to merge without the packaged-app `nix build .` check (no build files or dependencies changed; the Nix build runs the unit tests already green here). Fast-forward merged into `main` (head `ccdb8b3`).
