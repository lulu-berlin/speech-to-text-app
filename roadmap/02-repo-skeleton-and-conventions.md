# 02 — Repo skeleton and conventions

Status: in-progress
Depends on: [01](../docs/specs/dev-environment.md) (done)

## Goal

Create the minimal runnable backend and frontend, a minimal `flake.nix` as an early packaging check, the build commands in `AGENTS.md`, and the code conventions every later item follows.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Kotlin + Ktor backend serving a React + TS + styled-components frontend, one repo, coroutines, Nix flake deliverable.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): Kotest + `kotlinx-coroutines-test` for backend tests.
- [ADR 0009 — Approval-gated Canon TDD workflow](../docs/decisions/0009-approval-gated-canon-tdd-workflow.md): conventions are written with the user.
- [ADR 0010 — Development toolchain in devenv](../docs/decisions/0010-development-toolchain.md): tools come from devenv; agents add tools they need as their own commits.
- [ADR 0012 — Frontend dependency management: pnpm](../docs/decisions/0012-frontend-dependency-management-pnpm.md)
- [ADR 0013 — Backend structure: feature packages with Ktor DI](../docs/decisions/0013-backend-feature-packages-kotlin-di.md)
- [ADR 0014 — Build tooling](../docs/decisions/0014-build-tooling-gradle-catalog-no-wrapper.md)
- [ADR 0015 — Code formatting: ktlint and prettier](../docs/decisions/0015-code-formatting-ktlint-prettier.md) (user requirement added mid-item: `gradle check`/`pnpm test` enforce formatting)

## Proposal (approved on 2026-10-05)

- `backend/`: Gradle Kotlin DSL, single module; Ktor 3.6 with the Netty engine, kotlinx.serialization, Kotest; CI-less plain Gradle (no wrapper, ADR 0014).
  - `GET /api/health` → `{"status":"ok"}`.
  - Static files served from directory `STT_STATIC_DIR`, with SPA fallback to `index.html` for non-`/api` paths; unknown `/api/...` paths return 404, never `index.html`.
  - `STT_STATIC_DIR` unset → static serving disabled (non-`/api` paths 404), `/api/health` unaffected, startup logs a warning.
- `frontend/`: Vite + React + TypeScript (strict) + styled-components; Vitest + React Testing Library; dependencies via pnpm (ADR 0012); the Vite dev server proxies `/api` to the backend.
- Wiring via the Ktor DI plugin (`ktor-server-di`, ADR 0013).
- Configuration via env vars prefixed `STT_`:
  - `STT_PORT` (default 8080), `STT_DATA_DIR`, `STT_STATIC_DIR`, `STT_WHISPER_CLI`, `STT_WHISPER_MODEL`, `STT_SPEAKER_MODEL`, `STT_FFMPEG`, `STT_DEVICE`, `STT_THREADS`.
  - Invalid values fail fast at startup (clear error, no silent default).
- Minimal `flake.nix` that builds both parts (backend via `gradle.fetchDeps`, frontend via `fetchPnpmDeps`); finished in item 16.
- Append build-output ignores to `.gitignore`: `build/`, `.gradle/`, `node_modules/`, `dist/`, `result`.
- Write `docs/conventions/backend.md` and `docs/conventions/frontend.md` (approved 2026-10-05): package structure, naming, error handling, test style, component/file layout, theming.
- Replace the `AGENTS.md` Commands placeholder with the real commands (build, run, unit tests, `Integration`-tagged tests, frontend dev server and tests), and add the exact test commands to the Testing section.

## Decisions (consultation 2026-10-05)

| # | Question | Decision | Recorded in |
| -- | -------- | -------- | ----------- |
| 1 | Backend package structure | By feature (`stt/{config,http,queue,transcribe,audio,diarization,outputs,db}`) | ADR 0013, conventions |
| 2 | Kotest spec style | `BehaviorSpec` (given/when/then) | ADR 0006, conventions |
| 3 | Gradle version catalog (`gradle/libs.versions.toml`) | Yes | ADR 0014 |
| 4 | Frontend package manager | pnpm | ADR 0012 |
| 5 | Ktor dependency wiring | Ktor DI plugin (`ktor-server-di`) | ADR 0013 |
| 6 | Gradle wrapper vs devenv Gradle | No wrapper; devenv Gradle in dev, nixpkgs gradle in the Nix build | ADR 0014 |
| 7 | Minimal flake scope | Builds both parts now (`nix build .`); app wrapper and full `STT_*` env wiring finished in item 16 | ADR 0014 |

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-05. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] `GET /api/health` responds 200 with body exactly `{"status":"ok"}` (Ktor `testApplication`)
- [x] A request for an existing file under `STT_STATIC_DIR` returns that file with its content
- [x] A request for an unknown non-`/api` path returns `index.html` (SPA fallback)
- [x] A request for an unknown `/api/...` path returns 404, not `index.html`
- [x] With `STT_STATIC_DIR` unset, `/api/health` still responds 200 and an unknown non-`/api` path returns 404 (static serving disabled)
- [x] `STT_PORT` unset → port defaults to 8080 (config unit test)
- [x] `STT_PORT` set to a non-numeric value → startup fails with a clear error, no silent fallback to the default (config unit test)
- [x] The frontend root renders (one Vitest + RTL render test) — green in the user's devenv shell (`pnpm test`) and in-sandbox via node

## Acceptance criteria

- `backend/` and `frontend/` build and their test suites pass inside `devenv shell` using the commands written into `AGENTS.md` (frontend via pnpm).
- Running the backend with `STT_STATIC_DIR` pointing at the built frontend serves the UI at `http://localhost:8080` and `GET /api/health` returns `{"status":"ok"}`.
- User runs `nix build .` on the minimal `flake.nix` successfully (both parts; `deps.json` for `gradle.fetchDeps` and the `fetchPnpmDeps` store hash are generated by the user; cannot be verified inside the agent sandbox).
- `.gitignore` contains `build/`, `.gradle/`, `node_modules/`, `dist/`, `result`; `git status` is clean after a full build.
- `docs/conventions/backend.md` and `docs/conventions/frontend.md` exist and were approved by the user.
- `AGENTS.md` Commands section lists the real commands.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-05: consultation decisions 1–7 approved as presented; `STT_STATIC_DIR`-unset behavior (static disabled, 404 for non-`/api`, health unaffected) approved; test list of 8 items approved; `docs/conventions/backend.md` and `docs/conventions/frontend.md` drafts approved; ADRs 0012–0014 approved. User allowed an ephemeral sandbox `pnpm` install (session-only, for agent verification; the real provision is the committed `pkgs.pnpm` in devenv.nix).