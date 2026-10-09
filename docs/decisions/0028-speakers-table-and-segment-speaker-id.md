# 0028 — Speakers table; segments reference their speaker by `speaker_id`

Status: accepted · Date: 2026-10-09

## Context
`V1__init.sql` (item 03, [ADR 0019](0019-schema-ownership-and-evolution.md)) gave `segments` a free-text `speaker TEXT NULL` column; nothing has written it yet. Roadmap item 09 labels every segment with a 0-based speaker index and must persist it. The user plans a later feature: renaming a task's speakers after transcription ("Speaker 1" → a real name), persisted in the database. With the name stored on every segment, a rename would rewrite all of a task's segments; with an index on the segments and one row per speaker, it updates one row. The user asked for an explicit foreign key, in the database and in the code (item 09 consultation, 2026-10-09).

## Decision
- A new Flyway migration `V3__speakers.sql` (V1 is never edited, ADR 0019):
  - `speakers(task_id INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE, speaker_id INTEGER NOT NULL CHECK (speaker_id >= 0), speaker TEXT NOT NULL, PRIMARY KEY (task_id, speaker_id))`: one row per speaker of a task; `speaker_id` is the 0-based index the diarization assigns, `speaker` its name.
  - `segments` is rebuilt (SQLite cannot change a column's type or add a foreign key in place): `speaker TEXT` is replaced by `speaker_id INTEGER NULL` with `FOREIGN KEY (task_id, speaker_id) REFERENCES speakers (task_id, speaker_id)`; existing rows are copied with `speaker_id` NULL (the app never wrote `speaker`).
- The name is written by the app when a speaker is stored: `"Speaker ${speakerId + 1}"` (1-based, as the renderers number speakers). It is not a database `DEFAULT`, because SQLite defaults cannot refer to another column.
- `speaker_id` is NULL while a segment has no speaker: segments are stored per chunk during transcription, before any speaker exists, and tasks with `speakers_mode = off` are never diarized.
- Code mirrors the schema: a `SpeakersTable` Exposed object, and the composite foreign key declared on `SegmentsTable` with Exposed `foreignKey(...)`.
- Naming in code: the 0-based integer is always `speakerId` (`Segment.speakerId: Int?`); `speaker` only ever names the speaker's name, a non-null `String`.

## Consequences
- Renaming a speaker is an update of one `speakers` row; the rename feature itself is a later roadmap item.
- The database rejects a segment that names a speaker its task does not have.
- Storing a task's speakers writes `speakers` rows and `segments.speaker_id` in one transaction (item 09).
- Item 03's `Segment.speaker: String?` becomes `Segment.speakerId: Int?` at every call site; test fixtures that stored free-text speakers now store none, since a non-NULL `speaker_id` needs its `speakers` row.
