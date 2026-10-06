# 0018 — Audio payload stored as paged BLOBs

Status: accepted · Date: 2026-10-06 · Amended: 2026-10-06 (item 03 review round 1)

## Context
ADR 0005 stores uploaded audio in SQLite and requires that uploads and reads *stream* the BLOB — never load it fully into memory — so large files can be piped to temp files for ffmpeg/whisper-cli. The stack (ADR 0006) uses Exposed over the xerial `sqlite-jdbc` driver. A capability probe of `sqlite-jdbc` 3.49.1.0 (run 2026-10-06 in the agent sandbox) shows the driver cannot stream single-row BLOBs:

- `Connection.createBlob()`, `PreparedStatement.setBlob`, `ResultSet.getBlob` → `SQLFeatureNotSupportedException` ("not implemented by SQLite JDBC driver").
- `ResultSet.getBinaryStream()` returns a fully materialized `ByteArrayInputStream`.
- Writes are only possible as full values (`setBytes(byte[])`), materializing the payload in RAM.
- `readfile()`/`writefile()` (SQLite server-side file ⇄ BLOB functions) are not compiled into the driver build.

A single-row `data BLOB` therefore violates ADR 0005 for multi-GB uploads. This item's schema consultation (2026-10-06) also decided that audio is **kept after completion** (history playback in item 15; the DB is the only copy), and that deletion, if ever wanted, becomes an explicit feature.

## Decision
- The audio payload is stored as **fixed 1 MiB pages, one row per page**, in `audio_parts(task_id, part_no, part BLOB, PK (task_id, part_no))`, with `audio(task_id PK, size_bytes, sha256)` holding one metadata row per task. Both tables reference `tasks` with `ON DELETE CASCADE`.
- **At most one task per audio content (added 2026-10-06).** `audio.sha256` is `UNIQUE`: the same bytes uploaded again — under any filename — are a duplicate whose insert fails. A task and its audio rows are written in **one transaction**: a rejected duplicate rolls back the whole task creation, leaving no orphan task. The rule is strict — a FAILED or CANCELED task also blocks re-scheduling the same file until an explicit retry feature exists (none yet).
- **Write (amended in review round 1).** The repository first spools the upload stream into a temp file under `$STT_DATA_DIR/tmp` (ADR 0017), reading it in windows of at most 1 MiB and computing `size_bytes` and `sha256` (`MessageDigest`) on the way — before taking the database write lock, so a slow upload blocks no other database work. Then one short write transaction inserts the task row (task creation), the `audio` metadata row — a duplicate `sha256` fails here, at once, before any page is written; the constraint also guards concurrent identical uploads — and one `audio_parts` row per 1 MiB window read from the spool file. The temp file is deleted after success and after failure. Peak memory ≈ page size.
- **Read:** the repository streams pages in `part_no` order to an `OutputStream` (the temp file used by ffmpeg/whisper-cli), again one page in memory at a time.
- Task listings never touch `audio_parts`.
- Each page is a SQLite BLOB; the content model honors ADR 0005's intent — database is the single source of truth, payload is streamed in and out, listings exclude payload — while dropping its literal "one BLOB column" wording.

## Consequences
- Constant-memory audio handling regardless of file size, with the stock driver and standard JDBC/Exposed APIs.
- A 1 MiB page constant bounds row count (~1000 rows per GB) and per-query materialization; page size is a tunable constant, not part of the schema contract.
- The schema deviates from the draft single-`data`-BLOB row; recorded here so the deviation is explainable at review.
- `sha256` uniqueness (added 2026-10-06) makes duplicates a database-level guarantee, exposed to the API layer as a constraint rejection; the queue/HTTP mapping (409 vs reuse) is decided in item 12. Retrying a failed/canceled task will require an explicit feature (task reuse or deletion) because re-uploading the same bytes is a duplicate.
- Kept audio grows the database; deleting audio is an out-of-scope future feature (explicit user action, not a completion side effect).
- An upload temporarily needs about twice its size on disk (spool file + pages) and is written twice (amended in review round 1); this is the price of never holding the write lock while waiting on a client.
- The "blob streaming" behavior is locked by a test: a >1-page payload round-trips byte-for-byte through streams with independently derived `size_bytes`/`sha256`.
