# 02 — Repo skeleton and conventions

Status: draft
Depends on: [01](../docs/specs/dev-environment.md) (done)

## Goal

Create the minimal runnable backend and frontend, a minimal `flake.nix` as an early packaging check, the build commands in `AGENTS.md`, and the code conventions every later item follows.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Kotlin + Ktor backend serving a React + TS + styled-components frontend, one repo, coroutines, Nix flake deliverable.
- [ADR 0006 — Exposed, Flyway, Kotest](../docs/decisions/0006-exposed-flyway-kotest.md): Kotest + `kotlinx-coroutines-test` for backend tests.
- [ADR 0009 — Approval-gated Canon TDD workflow](../docs/decisions/0009-approval-gated-canon-tdd-workflow.md): conventions are written with the user.

## Proposal (pending approval)

- `backend/`: Gradle Kotlin DSL, single module; Ktor 3 with the Netty engine, kotlinx.serialization, Kotest.
  - `GET /api/health` → `{"status":"ok"}`.
  - Static files served from directory `STT_STATIC_DIR`, with SPA fallback to `index.html` for non-`/api` paths.
- `frontend/`: Vite + React + TypeScript + styled-components; Vitest + React Testing Library; the Vite dev server proxies `/api` to the backend.
- Configuration via env vars prefixed `STT_`:
  - `STT_PORT` (default 8080), `STT_DATA_DIR`, `STT_STATIC_DIR`, `STT_WHISPER_CLI`, `STT_WHISPER_MODEL`, `STT_SPEAKER_MODEL`, `STT_FFMPEG`, `STT_DEVICE`, `STT_THREADS`.
- Minimal `flake.nix` that builds both parts and runs them (early integration check; finished in item 16).
- Append build-output ignores to `.gitignore`: `build/`, `.gradle/`, `node_modules/`, `dist/`, `result`.
- Write `docs/conventions/backend.md` and `docs/conventions/frontend.md` together with the user: package structure, naming, error handling, test style (e.g. Kotest `FunSpec` vs `BehaviorSpec`), component/file layout, styled-components theming.
- Replace the `AGENTS.md` Commands placeholder with the real commands (build, run, unit tests, `Integration`-tagged tests, frontend dev server and tests), and add the exact test commands to the Testing section.

## Open questions for the user

- Package-by-feature or package-by-layer for the backend?
- Kotest spec style (`FunSpec`, `BehaviorSpec`, `DescribeSpec`, ...)?
- Gradle version catalog (`gradle/libs.versions.toml`): yes or no?
- npm or pnpm?
- Ktor dependency wiring: plain constructor wiring, Koin, or the Ktor DI plugin?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] `GET /api/health` responds 200 with `{"status":"ok"}` (Ktor `testApplication`)
- [ ] A request for an existing static file under `STT_STATIC_DIR` returns that file
- [ ] A request for an unknown non-`/api` path returns `index.html` (SPA fallback)
- [ ] An unknown `/api/...` path returns 404, not `index.html`
- [ ] The frontend app root renders (one Vitest + RTL render test)

## Acceptance criteria

- `backend/` and `frontend/` build and their test suites pass inside `devenv shell` using the commands written into `AGENTS.md`.
- Running the backend with `STT_STATIC_DIR` pointing at the built frontend serves the UI at `http://localhost:8080` and `GET /api/health` returns `{"status":"ok"}`.
- User runs `nix build .` on the minimal `flake.nix` successfully (cannot be verified inside the agent sandbox).
- `.gitignore` contains `build/`, `.gradle/`, `node_modules/`, `dist/`, `result`; `git status` is clean after a full build.
- `docs/conventions/backend.md` and `docs/conventions/frontend.md` exist and were approved by the user.
- `AGENTS.md` Commands section lists the real commands.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
