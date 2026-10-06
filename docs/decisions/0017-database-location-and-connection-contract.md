# 0017 — Database location, data directory, and connection contract

Status: accepted · Date: 2026-10-06 · Amended: 2026-10-06 (item 03 review rounds 1 and 2)

## Context
Item 03 fixes the SQLite schema (ADR 0006: Exposed + Flyway + SQLite via the xerial JDBC driver). The database file needs a location, and the config rules (conventions, `docs/conventions/backend.md`) require invalid values to fail fast at startup with an error naming the variable — never a silent fallback. `STT_STATIC_DIR` has a meaningful unset behavior (disabled + warning), but a data directory has no safe implicit default: any fallback location silently creates a fresh, empty, cwd-dependent database.

## Decision
- The database file is `$STT_DATA_DIR/stt.db`. No extra configuration variable.
- `STT_DATA_DIR` unset or empty → startup fails with an error naming `STT_DATA_DIR`, thrown by `Config.fromEnv()` like the `STT_PORT` rules.
- The directory is created when missing (`mkdir -p`); a path that exists as a non-directory, or whose creation/opening fails, also fails startup naming `STT_DATA_DIR`.
- The application opens the database at startup (the `Database` is the first Ktor DI service, ADR 0013).
- **Connection model (amended in review rounds 1 and 2).** `Database` owns **one writer connection**. On it, in order: `PRAGMA journal_mode = WAL` (the result must be `wal`), `PRAGMA foreign_keys = ON`, `PRAGMA journal_size_limit = 67108864` (64 MiB, added in review round 2). If a PRAGMA fails or the journal mode is not `wal`, the connection is closed and opening fails with an error naming `STT_DATA_DIR`. Write transactions take a suspending lock, so they run one at a time on the writer and never interleave. **Every read transaction opens its own fresh read-only connection**, closed when the transaction ends; WAL lets it run alongside the writer. Readers set no PRAGMAs: the journal mode is persisted in the file, foreign keys do not apply to read-only connections, and readers never write the WAL. No connection-pool dependency.
- **Migrations run with foreign keys off (amended in review round 1).** Flyway migrates on its own connection, before the `Database` is returned, with SQLite's default `foreign_keys = OFF`. This is deliberate: SQLite's documented table-rebuild procedure for schema changes (create new table, copy, drop old, rename) requires foreign keys off.
- **Temp directory (amended in review round 1).** `$STT_DATA_DIR/tmp` holds upload spool files (ADR 0018); it is created on open and its stale entries (left by a crash) are removed on open.
- Fail-fast database errors surface at boot: `gradle run` exits non-zero with a clear message; no lazy first-use failure.
- **Packaged app (amended in review round 1).** The flake's `bin/speech-to-text-app` wrapper checks `STT_DATA_DIR` before starting the JVM: unset or empty prints one line to stderr naming `STT_DATA_DIR` and exits non-zero, instead of an uncaught JVM stack trace. There is no default value.

## Consequences
- No implicit database location: boot is the failure point, not the first query.
- `journal_mode=wal` persists in the database file, but the writer applies and checks it on every open — and `foreign_keys` is per-connection and must not be assumed from a previous connection.
- The WAL grows to the size of the largest write transaction (a whole upload, ADR 0018), and SQLite reuses the file instead of shrinking it; the writer lives as long as the app, so without a limit a 2 GB upload would leave a 2 GB `stt.db-wal` until the process stops. `journal_size_limit` (review round 2) cuts the WAL back to at most 64 MiB when it is reset, i.e. when the first write transaction after a completed checkpoint commits — not by the checkpoint itself. 64 MiB is far above the steady-state WAL (about 4 MiB at SQLite's 1000-page automatic checkpoint), so ordinary writes never truncate and regrow the file.
- Reads never wait for writes (a slow upload transaction or a stalled `copyTo` does not block queries); writes still wait for each other. A read sees the last committed state.
- `Database.connection` (the writer) is reachable from tests; in a single Gradle module `internal` does not restrict app code, so app code must go through the repositories by convention.
- Tests create their own temp directories; the open/migrate path is exercised by the db tests.
- The data directory may hold the only copy of uploaded audio; it is a backup/restore unit (ADR 0005).
