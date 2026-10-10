# 26 — Engine start/stop race (flaky DatabaseDependencySpec)

Status: in-progress
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

## Diagnosis (proven 2026-10-10, workspace agent; Ktor 3.6.0 sources read at the 3.6.0 tag, matched by the resolved artifact)

Ktor 3.6.0 stops an application in this order (from `DependencyInjection.kt`, `Application.kt`, `EmbeddedServerJvm.kt`):

1. `ApplicationStopping` is raised. The DI plugin's own subscriber runs the DI cleanups there, **synchronously on the stopping thread**, iterating the registration map in **reverse registration order**, and only for keys whose deferred `tryGetCompleted()` is not null — an engine whose creation is still in flight is **skipped**. After the loop it calls `coroutineScope.cancel("Application stopped")`, which cancels the shared application job (the DI scope is built on `application.coroutineContext`).
2. `Application.disposeAndJoin()` then cancels and joins the application job — **after** the `Database` has already been closed in step 1.

Consequences, all probe-verified:

- The engine is created lazily (`launch { dependencies.resolve<ProcessingEngine>().start() }` on the application's `Dispatchers.Default` context). If the application stops while that resolution is in flight, no engine cleanup exists, the `Database` closes mid-loop, the engine's creation then finishes and calls `start()`, and the worker's first `tasks.recoverRunning()` → `writeTransaction` touches the closed writer connection.
- `dependencies.resolve` is suspend and cancellable only at suspension points; a provider already mid-flight finishes before its awaiting coroutine notices the cancel.
- The failing stack is exactly: `SQLException: database connection closed` at `SQLiteConnection.checkOpen` (line 410), reached through `stt.db.Database$writeTransaction$2$1` via Exposed's `JdbcConnectionImpl.getCatalog`/`metadata` (fetching the vendor for the default isolation level).
- The Ktor test engine rethrows uncaught coroutine exceptions into the test — that is how the Nix failure surfaced.
- Deterministic reproduction (throwaway probe, deleted): hold the engine's creation (its `ProcessRunner` dependency) on a gate; a DI cleanup hook registered after `sttApp`'s `dependencies {}` opens the gate once the `Database` is already closed. The failing test then fails identically to the Nix run (`java.sql.SQLException at SQLiteConnection.java:410`). Post-fix the gate is open before stop begins and the test passes.
- The real-world window: the engine's creation completes during the synchronous cleanup loop — the loop has real slack before the `Database` key (closing the speaker diarizer/model, `TaskWorkDirectories`, …), and the app job is still alive until the loop ends; the worker's write then races `Database.close()` (a WAL checkpoint). Under the loaded Nix sandbox, occasionally.

## Decision (2026-10-10, chosen with the user)

**Eager engine creation (option A).** `EngineModule.kt` adds `require(DependencyKey<ProcessingEngine>())` inside the `dependencies {}` block, so DI resolves the engine during `ApplicationModulesLoaded` (like `Database`, ADR 0017) and `startApplication()` returns only after the engine exists and its worker launched. DI's cleanup then always finds the engine completed and runs `engineJob.cancelAndJoin()` before the `Database` closes. Cost is engine creation at startup — pure wiring, the CAM++ model loads lazily on first diarization (probe-verified). Rejected: a shutdown hook on an earlier DI resource (couples to registration order); a suspend module (`suspend fun Application.processingEngine()`) resolving inline (same guarantee, but `sttApp`, `Main.kt` and every test `application {}` block go suspend).

Recorded as [ADR 0038](../docs/decisions/0038-engine-created-at-startup.md) in the merge.

## Test list (written with the user; Canon TDD step 1 — approved 2026-10-10)

Behaviors only.

- [ ] X1. **Race regression** — an application whose engine creation is still in flight when shutdown begins never runs engine code on a closed `Database`: holding the engine's creation open (its `ProcessRunner` dependency) until the stop sequence is past the engine's cleanup, no uncaught engine exception escapes the application, and the `Database` closes cleanly. (Deterministic repro: fails before the fix with the reported `SQLException`; passes after.)
- [ ] X2. **Normal stop** — with the engine already running, stopping the application still joins the engine's work (no uncaught exception, engine job completed, `Database` closed) — the existing engine cleanup behavior is preserved.
- [ ] X3. **Must not break: `DatabaseDependencySpec`** — DI still opens/closes the `Database`, and a test-provided `Database` still replaces the module's; the spec passes unchanged.
- [ ] X4. **Must not break: `EngineWiringSpec`** — the engine is created and its worker recovers a crashed RUNNING task without the test touching the engine ("only the app's startup can do it"); DI still provides the engine.
- [ ] X5. **Must not break: the engine specs and the Integration suite** — `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` stay green.

Out of scope, noted only:

- the sqlite-jdbc version drift (3.50.3.0 resolved vs 3.49.1.0 pinned);
- the `ExperimentalCoroutinesApi` opt-in warnings in the engine tests;
- the `blkid`/`hostname` "not found" lines in the sandbox.

## Open questions

- ~~Which fix (after the diagnosis)?~~ Settled 2026-10-10: eager engine creation (option A), above.
- Does the sqlite-jdbc drift get its own item? (Open; not part of this item.)

## Acceptance criteria

- The race is reproduced deterministically by a test that fails before the fix and passes after it.
- `gradle check --rerun-tasks` and `gradle integrationTest --rerun-tasks` are green.
- The user's `nix build .` passes, repeated as the user sees fit.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10: drafted by the root agent from the user's failing `nix build .` log and item 17's agent's analysis. At the user's request, the investigation, the consultation (spec and test list) and the fix are delegated to the workspace `workspaces/26-engine-shutdown-race`. The approval lines are recorded in the worktree's copy and reach `main` with the merge.
- 2026-10-10: diagnosis proven (probes + Ktor 3.6.0 sources; evidence above); fix decision (eager engine creation, option A) and test list X1–X5 written with the user and approved; status `draft` → `spec-approved`. ADR 0038 accompanies the fix.
- 2026-10-10: worktree `workspaces/26-engine-shutdown-race` started (branch `roadmap/26-engine-shutdown-race`); status → `in-progress`. Canon TDD begins at X1.
