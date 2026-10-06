# 0017 — Database location, data directory, and connection contract

Status: accepted · Date: 2026-10-06

## Context
Item 03 fixes the SQLite schema (ADR 0006: Exposed + Flyway + SQLite via the xerial JDBC driver). The database file needs a location, and the config rules (conventions, `docs/conventions/backend.md`) require invalid values to fail fast at startup with an error naming the variable — never a silent fallback. `STT_STATIC_DIR` has a meaningful unset behavior (disabled + warning), but a data directory has no safe implicit default: any fallback location silently creates a fresh, empty, cwd-dependent database.

## Decision
- The database file is `$STT_DATA_DIR/stt.db`. No extra configuration variable.
- `STT_DATA_DIR` unset or empty → startup fails with an error naming `STT_DATA_DIR`, thrown by `Config.fromEnv()` like the `STT_PORT` rules.
- The directory is created when missing (`mkdir -p`); a path that exists as a non-directory, or whose creation/opening fails, also fails startup naming `STT_DATA_DIR`.
- The application opens the database at startup (the connection is the first Ktor DI service, ADR 0013). On every opened connection, in order: `PRAGMA journal_mode = WAL`, `PRAGMA foreign_keys = ON`, then Flyway migrations run.
- Fail-fast database errors surface at boot: `gradle run` exits non-zero with a clear message; no lazy first-use failure.

## Consequences
- No implicit database location: boot is the failure point, not the first query.
- `journal_mode=wal` persists in the database file, but the pragmas are still applied on every open — `foreign_keys` in particular is per-connection and must not be assumed from a previous connection.
- Tests create their own temp directories; the open/migrate path is exercised by the db tests.
- The data directory may hold the only copy of uploaded audio; it is a backup/restore unit (ADR 0005).