# 26 — Engine start/stop race (flaky DatabaseDependencySpec)

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done)

## Goal

Make application shutdown safe whatever the processing engine's startup is doing at the time, so no engine code ever touches a closed `Database`. Then `DatabaseDependencySpec` (and any short-lived `testApplication`) passes every time, including in the Nix build's `checkPhase`.

## Evidence

- The user's `nix build .` (2026-10-10) failed once in the backend derivation's `checkPhase` (`gradle check`, 186 tests). A rerun on the same source passed.
  - Failing test: `stt.http.DatabaseDependencySpec`, "it opens $STT_DATA_DIR/stt.db and provides it via DI; a Database the test provides first replaces it unopened".
  - Error: `java.sql.SQLException at SQLiteConnection.java:410`.
- Item 17's workspace agent:
  - could not reproduce it (25/25 passes for that spec alone; full-suite runs under CPU load were cancelled before they reported);
  - found that the runtime sqlite-jdbc is 3.50.3.0, not the catalog's 3.49.1.0, because another dependency pulls in the newer one;
  - found that line 410 there is `checkOpen()` ("database connection closed"), so a query ran on a `Database` that had already been closed.
- The Nix backend derivation uses `src = self`, so any repository change (frontend-only included) re-runs the whole backend suite. That is why the failure appeared on a frontend branch.

## Suspect (not proven)

`backend/src/main/kotlin/stt/engine/EngineModule.kt`:

- The `ProcessingEngine` is created lazily: `launch { dependencies.resolve<ProcessingEngine>().start() }` runs on the application's scope, after the module returns.
- The engine's DI `cleanup` (`engineJob.cancelAndJoin()`) only runs for an engine instance that exists. If the app stops before that coroutine has resolved the engine, nothing makes the `Database` close wait for the engine.
- The coroutine could then create the engine on the closed database. `start()` launches `tasks.recoverRunning()` on `engineScope`, which would throw the `SQLException`; Ktor's test engine surfaces it in the test.
- The test stops the app almost immediately after `startApplication()`, and the Nix sandbox runs the suite under load. Both widen the window.
- Unverified: the order in which Ktor 3.6.0 cancels the application job, raises `ApplicationStopping`/`ApplicationStopped`, and runs DI cleanups (including the cleanup of a `Database` the test provides itself).

## Proposal

To be settled with the user. Diagnosis comes before the fix:

1. Get the full stack trace: from the user's `nix log …stt-backend…drv` if it has one, or from a deterministic reproduction.
2. Build a deterministic reproduction: a test that forces the interleaving (for example, holding the engine's creation until after the stop has begun), rather than a stress loop.
3. Fix, choosing with the user. Candidates:
   - create the engine eagerly inside the module, so its cleanup always exists before the `Database` can close;
   - tie the cancel-and-join of `engineJob` to application shutdown independently of whether the engine instance exists;
   - something the diagnosis shows is better.

Out of scope, noted only:

- the sqlite-jdbc version drift (3.50.3.0 resolved vs 3.49.1.0 pinned);
- the `ExperimentalCoroutinesApi` opt-in warnings in the engine tests;
- the `blkid`/`hostname` "not found" lines in the sandbox.

## Open questions

- Which fix (after the diagnosis)?
- Does the sqlite-jdbc drift get its own item?

## Test list (written with the user; Canon TDD step 1)

To be written with the user after the diagnosis. Behaviors only. Expected scope:

- stopping the application at any point of engine startup never runs engine code on a closed `Database`;
- the engine's cleanup still runs before the `Database` closes when the engine is running;
- existing behavior keeps passing (`DatabaseDependencySpec`, `EngineWiringSpec`, the engine specs, the Integration suite).

## Acceptance criteria

- The race is reproduced deterministically by a test that fails before the fix and passes after it.
- `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` are green.
- The user's `nix build .` passes, repeated as the user sees fit.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10: drafted by the root agent from the user's failing `nix build .` log and item 17's agent's analysis. At the user's request, the investigation, the consultation (spec and test list) and the fix are delegated to the workspace `workspaces/26-engine-shutdown-race`. The approval lines are recorded in the worktree's copy and reach `main` with the merge.
