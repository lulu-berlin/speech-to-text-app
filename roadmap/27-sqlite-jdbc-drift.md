# 27 — Pin the sqlite-jdbc version

Status: spec-approved
Depends on: —

## Goal

Make the resolved sqlite-jdbc artifact match the version the backend's version catalog pins, so the runtime JDBC driver is the one the code was written and verified against (and item 26's `SQLiteConnection.checkOpen()` line-number references stay truthful). The pin becomes authoritative: no transitive dependency can silently override it again.

## Evidence (from the consultation, 2026-10-10)

- The backend catalog pins `sqlite-jdbc` 3.49.1.0; `gradle dependencyInsight` on the runtime classpath shows the resolution is 3.50.3.0:
  - `org.flywaydb:flyway-database-nc-sqlite:13.9.0` declares sqlite-jdbc 3.50.3.0, and Gradle's highest-wins conflict resolution overrides the catalog pin (`3.49.1.0 -> 3.50.3.0`).
- Every test in the repo — including item 26's flaky-failure diagnosis, which decompiled 3.50.3.0 — has been running against 3.50.3.0 all along; the catalog just does not say so.
- Item 26's failing call site (`SQLiteConnection.checkOpen`, line 410 in 3.50.3.0) is version-specific.

## Proposal (approved 2026-10-10)

- **Align the catalog pin up to 3.50.3.0**, the version that actually resolves and that every suite already runs against. Forcing the driver *down* to 3.49.1.0 was rejected: it fights `flyway-database-nc-sqlite`'s own requirement and changes the runtime everything was verified against, for zero benefit.
- **Make the pin authoritative**: `resolutionStrategy.force(libs.sqlite.jdbc)` in the backend's Gradle config, so no transitive dependency can ever resolve over the catalog again (user decision 2026-10-10, option (a)).
- Since 3.50.3.0 is already the resolved version, the fixed-output Gradle→Nix lockfile `backend/deps.json` should come out unchanged; the user verifies with `nix build .` (ADR 0014/0016). If it does change, it regenerates with a new hash from the user.

## Open questions (answered 2026-10-10)

- How authoritative should the pin be? → **Force** via `resolutionStrategy.force(...)` referencing the catalog version; nothing can resolve over it again.
- Align up or force down? → **Align up to 3.50.3.0**; forcing down was rejected (see Proposal).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-10 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The runtime sqlite-jdbc equals the catalog version 3.50.3.0: `gradle dependencyInsight` output recorded in the log, plus a pinned unit test asserting the loaded driver's version (`SQLiteJDBCLoader.getVersion()`), commented "must equal the catalog pin — update both together"
- [ ] A transitive dependency cannot silently override the pin: `dependencyInsight` shows the catalog version with "forced" selection reasons
- [ ] Must not break: `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` stay green against the pinned driver (Database open, WAL, Flyway migrations, the engine)
- [ ] The user's `nix build .` succeeds; `deps.json` is regenerated only if the resolved graph changed (expected: unchanged)

## Acceptance criteria

- The catalog, the resolution and the runtime driver all say 3.50.3.0.
- `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` green; the user's `nix build .` succeeds.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10: drafted by the root agent at the user's decision (item 26's open question → this item: "the sqlite-jdbc drift gets its own item").
- 2026-10-10: consultation with the user, who confirmed the evidence (`flyway-database-nc-sqlite:13.9.0` pulls 3.50.3.0 over the 3.49.1.0 pin): align the catalog pin up to 3.50.3.0 and force it via `resolutionStrategy` so no transitive can override it (option (a)); test list (4 items) approved; status → `spec-approved`.
