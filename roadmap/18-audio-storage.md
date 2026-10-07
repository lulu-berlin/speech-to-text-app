# 18 — Audio storage: originals in the work directory, chunks as FLAC

Status: draft
Depends on: [03](../docs/specs/database-schema.md) (done)

## Goal

Move the audio storage to ADR 0024: the original upload is no longer stored in the database but kept in the task's work directory until chunking, and the chunk FLACs are stored in the database. This item owns the `V2` migration and the repository changes; the engine (item 11) uses them.

## Already decided

- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole design; supersedes ADRs 0005 and 0018.
- [ADR 0019 — Schema ownership and evolution](../docs/decisions/0019-schema-ownership-and-evolution.md): `V1__init.sql` is never edited; the change arrives as a new versioned migration.
- [ADR 0017 — Database location and connection contract](../docs/decisions/0017-database-location-and-connection-contract.md): `$STT_DATA_DIR` layout, upload spools in `tmp/`, one writer behind a lock, fresh read-only connections for reads.
- [Item 03 spec](../docs/specs/database-schema.md): the current schema, repositories and their tests.

## Proposal (pending approval)

- **Migration `V2`:** drop `audio_parts`; create `chunk_audio(task_id INTEGER NOT NULL, idx INTEGER NOT NULL, flac BLOB NOT NULL, PRIMARY KEY (task_id, idx), FOREIGN KEY (task_id, idx) REFERENCES chunks (task_id, idx) ON DELETE CASCADE)`. Matching Exposed table object; `AudioPartsTable` removed.
- **Upload (`TaskRepository.createWithAudio`):** spool the source into `$STT_DATA_DIR/tmp` while computing `size_bytes` and `sha256` (unchanged); one short write transaction inserts the task row and the `audio` row (a duplicate `sha256` is rejected there, unchanged); after the commit, rename the spool to `$STT_DATA_DIR/work/<task-id>/source`, creating the directory. A duplicate or any failure before the rename deletes the spool. No audio bytes go into the database.
- **`AudioRepository`:** `write` and `copyTo` (the original's pages) are removed; `metadata` stays.
- **Chunk audio:** one write transaction stores a task's chunk plan together with every chunk's FLAC, read from the files item 06 exported into the work directory, one file in memory at a time. A read operation writes one chunk's FLAC to an `OutputStream` (one BLOB, read on a fresh read-only connection). Plan queries (`ChunkRepository.findByTask`) never read `chunk_audio`.
- **Item 03 tests** whose subject was the original's pages (`audio_parts` round-trip, write-side memory, `copyTo` stall, the listing test that hides `audio_parts`) are replaced by tests of the new behavior; the exact list is agreed at this item's consultation.

## Open questions for the user

- Where is the work directory path computed: on `Database` (it already knows `$STT_DATA_DIR`), or a small shared type used by this item and item 11?
- Does the plan-only `ChunkRepository.insertAll` stay (item 11 might never store a plan without its audio), or is it replaced by the plan-with-audio operation?
- Should `TaskRepository.createWithAudio` keep its name, now that it stores no audio bytes in the database?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] `V2` applies to a database at `V1`: `audio_parts` is gone, `chunk_audio` exists, and existing tasks, `audio` rows and chunks are kept
- [ ] Creating a task with audio stores no audio bytes in the database and leaves the original byte-identical at `work/<task-id>/source`, with `size_bytes`/`sha256` derived independently
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

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
