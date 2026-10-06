# 0018 — Audio payload stored as paged BLOBs

Status: accepted · Date: 2026-10-06

## Context
ADR 0005 stores uploaded audio in SQLite and requires that uploads and reads *stream* the BLOB — never load it fully into memory — so large files can be piped to temp files for ffmpeg/whisper-cli. The stack (ADR 0006) uses Exposed over the xerial `sqlite-jdbc` driver. A capability probe of `sqlite-jdbc` 3.49.1.0 (run 2026-10-06 in the agent sandbox) shows the driver cannot stream single-row BLOBs:

- `Connection.createBlob()`, `PreparedStatement.setBlob`, `ResultSet.getBlob` → `SQLFeatureNotSupportedException` ("not implemented by SQLite JDBC driver").
- `ResultSet.getBinaryStream()` returns a fully materialized `ByteArrayInputStream`.
- Writes are only possible as full values (`setBytes(byte[])`), materializing the payload in RAM.
- `readfile()`/`writefile()` (SQLite server-side file ⇄ BLOB functions) are not compiled into the driver build.

A single-row `data BLOB` therefore violates ADR 0005 for multi-GB uploads. This item's schema consultation (2026-10-06) also decided that audio is **kept after completion** (history playback in item 15; the DB is the only copy), and that deletion, if ever wanted, becomes an explicit feature.

## Decision
- The audio payload is stored as **fixed 1 MiB pages, one row per page**, in `audio_parts(task_id, part_no, part BLOB, PK (task_id, part_no))`, with `audio(task_id PK, size_bytes, sha256)` holding one metadata row per task. Both tables reference `tasks` with `ON DELETE CASCADE`.
- **Write:** the repository reads the upload stream in 1 MiB windows and inserts one `audio_parts` row per window inside a single transaction, computing `size_bytes` and `sha256` (`MessageDigest`) on the fly. Peak memory ≈ page size.
- **Read:** the repository streams pages in `part_no` order to an `OutputStream` (the temp file used by ffmpeg/whisper-cli), again one page in memory at a time.
- Task listings never touch `audio_parts`.
- Each page is a SQLite BLOB; the content model honors ADR 0005's intent — database is the single source of truth, payload is streamed in and out, listings exclude payload — while dropping its literal "one BLOB column" wording.

## Consequences
- Constant-memory audio handling regardless of file size, with the stock driver and standard JDBC/Exposed APIs.
- A 1 MiB page constant bounds row count (~1000 rows per GB) and per-query materialization; page size is a tunable constant, not part of the schema contract.
- The schema deviates from the draft single-`data`-BLOB row; recorded here so the deviation is explainable at review.
- Kept audio grows the database; deleting audio is an out-of-scope future feature (explicit user action, not a completion side effect).
- The "blob streaming" behavior is locked by a test: a >1-page payload round-trips byte-for-byte through streams with independently derived `size_bytes`/`sha256`.