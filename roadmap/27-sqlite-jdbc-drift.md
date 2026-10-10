# 27 — Pin the sqlite-jdbc version

Status: draft
Depends on: —

## Goal

Make the resolved sqlite-jdbc artifact match the version the backend's version catalog pins, so the runtime JDBC driver is the one the code was written and verified against (and item 26's `SQLiteConnection.checkOpen()` line-number references stay truthful).

## Evidence (from roadmap item 26's diagnosis, 2026-10-10)

- The backend catalog pins `sqlite-jdbc` 3.49.1.0.
- The runtime classpath resolves **3.50.3.0**: another dependency pulls in the newer version. The agent that diagnosed item 26 found it by decompiling the runtime jar.
- The driver version appeared while chasing the flaky `DatabaseDependencySpec` failure; the failing call site (`SQLiteConnection.checkOpen`, line 410 in 3.50.3.0) is version-specific.

## Proposal

To be written with the user. Expected scope: identify the dependency that pulls 3.50.3.0 in (Gradle `dependencyInsight`), decide whether to pin the catalog version explicitly (forcing 3.49.1.0), upgrade the catalog to 3.50.3.0, or constrain the transitive one — and why. `deps.json` (the Nix lockfile for Gradle deps) follows any catalog change; the user verifies with `nix build .` and `gradle check`.

## Open questions

- Who pulls 3.50.3.0 in, and does a pin/upgrade change behavior?
- Any follow-up from the version bump (API changes between 3.49.x and 3.50.x)?

## Test list (written with the user; Canon TDD step 1)

To be written with the user. The catalog and lockfile change is verified by `gradle check`/`integrationTest` and the user's `nix build .`, not by a new unit test.

## Acceptance criteria

- The resolved driver equals the catalog's pin; the drift is impossible or explained.
- `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` green; the user's `nix build .` succeeds.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10: drafted by the root agent at the user's decision (item 26's open question → this item: "the sqlite-jdbc drift gets its own item").
