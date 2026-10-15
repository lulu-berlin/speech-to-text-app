# 0051 — Existing databases rebuild the canonical clip inside a Flyway migration

Status: accepted · Date: 2026-10-14

## Context

Item 44 ([docs/specs/audio-in-database.md](../specs/audio-in-database.md)) makes the canonical clip the
only audio that playback and diarization read. A database created before the item holds each task's audio only
as its stored chunk FLACs ([ADR 0024](0024-audio-storage-original-until-chunking-flac-chunks.md)); after the
upgrade such a task has no canonical rows, so `/audio` would answer `404 no-audio` and diarization would have
nothing to decode. The item's consultation already decided *what* happens — "rebuild the canonical blob from
the stored chunk FLACs" — but not *how*: the rebuild must decode and re-encode audio, which SQL cannot do.

Options weighed (2026-10-14):

- **A Flyway class migration** (this decision): a Kotlin `BaseJavaMigration` that runs ffmpeg during
  `Flyway.migrate()`. One-time, tracked in `flyway_schema_history`, and no legacy read path is left in the app.
- **An engine backfill pass at startup**: the same work in application code, on every start until it is done,
  with progress the schema does not record.
- **A lazy rebuild on first playback or diarization**: cheapest at upgrade, but it keeps the legacy chunk-FLAC
  read path (the code item 44 removed) inside the serving route and diarization forever, and the first play of
  an old task is slow.

The user chose the Flyway class migration: a failed ffmpeg run fails the upgrade either way (the user's note:
"My question is how a failure would be resolved otherwise at startup? It's the same"), and the schema
migration is the right place for a one-time data rebuild.

## Decision

- The backfill is **`V10__BackfillCanonicalClip`**, a Kotlin Flyway migration under `stt/db/migration`.
  `Database.open` registers it with Flyway (`javaMigrations`) and hands it its scratch root, `backfill/` in the
  app's scratch directory ([ADR 0052](0052-canonical-clip-in-database-chunks-as-windows.md)); the SQL migrations
  stay in `classpath:db/migration`. (Amended 2026-10-14, review: it was found by scanning
  `classpath:stt/db/migration` and wrote to its own temp directory, which a kill mid-backfill left behind for good.)
- It selects every task with `chunk_audio` rows and no `audio_header` row, writes its chunk FLACs in `idx`
  order into that scratch, and rebuilds the clip with **one ffmpeg run** — the concat demuxer plus the canonical
  flags (`-ar 16000 -ac 1 -sample_fmt s16 -c:a flac -frame_size 4000`) — then splits it with `FlacSplitter` and
  inserts the header row and the one-second rows. The scratch is removed per task, success or failure, and a kill
  mid-backfill leaves it where the next start empties it.
- `V11__drop_chunk_audio.sql` then drops `chunk_audio`: every chunked task has its clip, and chunks are windows
  into it (ADR 0052).
- One ffmpeg run, not a copy followed by a re-encode: decoding the concat and encoding the canonical FLAC in
  one pass is what makes a chunk whose declared length is wrong irrelevant (the defect
  [ADR 0045](0045-joined-playback-flac-reencoded.md) fixed).
- ffmpeg runs through the app's own `ProcessRunner` inside `runBlocking`: a Flyway migration is blocking by
  contract and already runs on `Dispatchers.IO` from `Database.open` — the same bridge the engine's DI uses.
- The migration is **idempotent per task** (a task with a header is skipped), so a re-run after a repaired
  failure resumes; a failure aborts the upgrade with the data directory's migration error, like any other
  migration's.
- ktlint's class-naming rule is disabled for that one directory in `.editorconfig`: Flyway's
  `V<version>__<Description>` class name is the tool's contract.

## Consequences

- An existing installation upgrades in place and its finished tasks play; no legacy chunk-FLAC read path stays
  in the serving route, the engine or diarization.
- The upgrade can be slow for a library with many legacy tasks (one ffmpeg run each) and blocks application
  startup while it runs.
- A failed backfill fails the upgrade; as with any DML migration, retrying means Flyway's history is repaired
  (or the migration re-runs cleanly if Flyway rolled the failed attempt back — the per-task skip makes a
  re-run safe either way).
- The migration needs the disk space of one task's clip at a time, in `java.io.tmpdir`.
- Only already-chunked tasks are backfilled. A legacy task that was never chunked has no stored audio in the
  database at all (its original lived as a file in `work/<id>/source`, ADR 0024), and the user decided
  (2026-10-15: the app has no users and no stable release yet; "giving up on unfinished tasks before migrating
  is acceptable") not to migrate it. **`V12__GiveUpUnmigratedTasks`**, registered by `Database.open` with the
  data directory like V10, gives up on every such task explicitly: each task that is not COMPLETED and has
  neither a stored clip nor a stored original ends FAILED with the error "This task's audio from before the
  upgrade was not migrated. Delete the task and upload the file again." (CANCELED ones too; a finish time is kept),
  and the pre-item-44 `work/` and `tmp/` directories are removed from the data directory. Chunked tasks are
  unaffected.
- This decision is the migration half only; the storage design that replaces ADR 0024 is
  [ADR 0052](0052-canonical-clip-in-database-chunks-as-windows.md).
