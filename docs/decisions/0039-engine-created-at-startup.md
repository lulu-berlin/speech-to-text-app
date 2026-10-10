# 0039 — The processing engine is created when the application starts

Date: 2026-10-10

Status: accepted

## Context

Roadmap item 26, "Engine start/stop race": the engine was created lazily, in a
`launch { dependencies.resolve<ProcessingEngine>().start() }` on the
application's scope that ran after the DI module returned. Ktor 3.6.0 runs DI
cleanups synchronously on `ApplicationStopping`, in reverse registration order,
and only for dependencies whose creation has already completed; the
application job is cancelled and joined afterwards. Stopping while the engine's
creation was still in flight therefore closed the `Database` first, and the
engine's worker then ran its `recoverRunning()` write on the closed connection
(`SQLException: database connection closed` at `SQLiteConnection.checkOpen`,
line 410 in sqlite-jdbc 3.50.3.0). The race was reproduced deterministically in
a test that held the engine's `ProcessRunner` dependency open until the stop
sequence had passed the engine's cleanup key. Full evidence in the item's
roadmap file.

## Decision

The engine is created as part of application startup: the engine module
requires its own `ProcessingEngine` from DI within the `dependencies {}` block,
exactly like the `Database` (ADR 0017), so `ApplicationModulesLoaded` resolves
it and `startApplication()` returns only after the engine exists and its worker
coroutine has been launched. The worker still starts via
`launch { …start() }`, and engine shutdown still happens in the engine's DI
`cleanup` (`engineJob.cancelAndJoin()`), which now always exists before the
`Database` can close.

The CAM++ speaker model stays lazy: creating the engine at startup is wiring
only, the ONNX session still loads on the first diarization.

## Consequences

- Application shutdown can no longer overtake engine creation; any stop joins
  the engine before closing the `Database`.
- Startup is now fail-fast for engine wiring problems, matching the existing
  `Database` requirement rather than failing silently in a background coroutine.
- A module that needs the engine for its own startup (health routes, etc.)
  can resolve it once and share the instance.
- Not changed: the one-engine-per-application lifecycle (ADR 0013), the
  application-level `SupervisorJob` scope, and the engine's own shutdown
  semantics.