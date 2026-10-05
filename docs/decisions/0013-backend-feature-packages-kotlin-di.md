# 0013 — Backend structure: feature packages with Ktor DI

Status: accepted · Date: 2026-10-05

## Context
Roadmap item 02 fixes the backend's package structure and dependency wiring for every later item. The consultation asked package-by-feature vs package-by-layer, and plain constructor wiring vs Koin vs Ktor DI. ADR 0001 already fixes the stack; the component graph ahead (config, database, queue, engine, process runner, event stream, routes) is roughly 6–8 singletons.

## Decision
- **Package structure: by feature.** Packages per domain — `stt/config`, `stt/http`, `stt/queue`, `stt/transcribe`, `stt/audio`, `stt/diarization`, `stt/outputs`, `stt/db` — each owning its types, routes, and tests. `db/` is a first-class feature package, not an infrastructure layer.
- **Wiring: the Ktor DI plugin** (`ktor-server-di`, first-party in Ktor 3). Dependencies are registered in the application module's `dependencies {}` block (constructor/class/lambda providers); services are singletons unless a spec says otherwise; multiple providers of one type use named dependencies. Tests override dependencies in `testApplication` via `dependencies.provide<...>` before `loadServices()` (the test engine uses `IgnoreConflicts`).

## Consequences
- Feature change impact is localized; concurrent features do not collide in shared layers.
- The object graph is declared in one place (the application module) instead of a hand-built `main()`; coroutine-scope ownership stays explicit at boot (ADR 0001) and the scope object is provided through DI like any other service.
- Test setup for route tests overrides collaborators at the DI boundary rather than by constructor swapping.
- Ktor DI's docs are newer than Koin's; Koin is not used.