# 0019 — Schema ownership: one schema in item 03, migrations after

Status: accepted · Date: 2026-10-06

## Context
ADR 0004 makes persistence of the chunk plan and per-chunk segments a core design element: tasks resume from their last finished chunk, so `chunks` (DONE flags) and `segments` are persisted data, not just processing artifacts. Roadmap items 06 (audio decoding and chunking) and 07 (whisper transcription) both list "add 03 as a dependency" as open questions, leaving open whether their tables belong here. Splitting the schema across items would scatter one `CREATE` story, the Exposed mirrors, and the repositories over three items and three migrations.

## Decision
- The **full schema — `tasks`, `audio`, `audio_parts`, `chunks`, `segments`, `queue_state` — lands once**, in item 03's `V1__init.sql`, with its Exposed table objects and repositories (`TaskRepository`, `AudioRepository`, `ChunkRepository`, `SegmentRepository`, `QueueStateRepository`) in the `stt/db` feature package.
- Later items **never edit `V1__init.sql`**: any schema change arrives as a new versioned Flyway migration with matching Exposed objects. This resolves items 06/07's "add 03 as a dependency" open questions: they depend on item 03 for their tables and repositories.
- Enum-like columns (`status`, `phase`, `speakers_mode`, `chunks.status`, …) are enforced with SQLite `CHECK` constraints at the database; relaxing or extending an enum requires a migration.

## Consequences
- Items 06/07 become pure processing logic over an existing, reviewed schema; the resume contract is tested in the item that owns the data.
- A coherent `V1__init.sql` instead of three partial ones; later migrations are additive and independently reviewable.
- `CHECK` constraints give defense in depth: invalid values are rejected by the database even if a caller skips domain validation.
- Item boundaries stay explicit: schema evolution is versioned, so an unforeseen column in 06/07 is a normal `V2`-style migration, not a rewrite.