# Backend conventions

Approved 2026-10-05 in [roadmap item 02](../roadmap/02-repo-skeleton-and-conventions.md) (now spec-approved). Every backend item follows these rules; deviations need a new ADR.

## Package structure

Feature packages under `src/main/kotlin`, one package per feature; tests mirror the structure under `src/test/kotlin`:

```
src/main/kotlin/stt/      Main.kt (boot: config, coroutine scope, Ktor app)
  config/                 Config + STT_* env parsing
  http/                   Ktor application composition, routes, static/SPA serving
  queue/  transcribe/  audio/  diarization/  outputs/  db/   (added by later items)
```

- A feature owns its types, routes, and tests; no cross-feature imports except through explicit interfaces.
- `db/` is a feature like any other (ADR 0013); infrastructure is a first-class package, not a layer.

## Naming

- Directories: lowercase, single word, no underscores.
- Types: PascalCase, `KotlinFile.kt` per type; route groups `<Feature>Routes.kt`.
- Tests: `<Subject>Spec.kt`, same package as the subject.
- Environment variables: `STT_` prefix only, read in exactly one place (see Config).

## Config

- All environment access goes through `stt/config/Config.kt` (`Config.fromEnv()`).
- Invalid values fail fast: startup aborts with a clear message; never silently fall back to a default for a set-but-invalid variable.
- Defaults live in the spec (`STT_PORT` = 8080; `STT_STATIC_DIR` unset = static serving disabled).

## Dependency injection (ADR 0013)

- Ktor DI (`ktor-server-di`): registration in the application module's `dependencies {}` block.
- Services are singletons; named dependencies where more than one provider exists; resolve explicitly with `@Named`.
- Tests override dependencies in `testApplication` via `dependencies.provide<...>` **before** `loadServices()`; the test engine's `IgnoreConflicts` policy makes overrides free.

## Error handling

- Unknown `/api/...` → 404 (JSON), never the SPA fallback. The `/api` boundary and the static/SPA logic are kept apart so no static-file change can break it.
- Domain failures map to HTTP errors with JSON bodies; no stack traces in responses; unexpected errors → 500.
- 4xx/5xx shapes stay constant across items unless a later spec changes them.

## Concurrency (ADR 0001)

- All async work on Kotlin coroutines; an application-level `CoroutineScope` (with `SupervisorJob`) is created in `Main.kt` and provided via DI.
- Blocking I/O (JDBC, subprocesses, file I/O) only inside `withContext(Dispatchers.IO)`.
- No raw `Thread`, `ExecutorService`, or `CompletableFuture` in app code.

## Tests (ADR 0006)

- Kotest `BehaviorSpec` (given/when/then); scenario wording matches the item's test list.
- HTTP behavior: Ktor `testApplication`, assert status and body (kotlinx.serialization).
- File fixtures: temp directories per test (Kotest `TempDirectory`), never fixtures committed to the repo.
- Tests needing real ffmpeg, whisper-cli, or ONNX models are tagged `Integration` and run separately. Engine/queue coroutine logic uses `kotlinx-coroutines-test` (`runTest`, virtual time).