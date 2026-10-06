# Repo skeleton and conventions

Status: done (roadmap item 02, merged 2026-10-06)
Depends on: [01 — dev environment](dev-environment.md)

## Goal

Create the minimal runnable backend and frontend, a minimal `flake.nix` as an early packaging check, the build commands in `AGENTS.md`, and the code conventions every later item follows.

## Decisions

- [ADR 0001 — Stack and monorepo](../decisions/0001-stack-and-monorepo.md): Kotlin + Ktor backend serving a React + TS + styled-components frontend, one repo, coroutines, Nix flake deliverable.
- [ADR 0006 — Exposed, Flyway, Kotest](../decisions/0006-exposed-flyway-kotest.md): Kotest + `kotlinx-coroutines-test` for backend tests.
- [ADR 0009 — Approval-gated Canon TDD workflow](../decisions/0009-approval-gated-canon-tdd-workflow.md): conventions are written with the user.
- [ADR 0010 — Development toolchain in devenv](../decisions/0010-development-toolchain.md): tools come from devenv; agents add tools they need as their own commits.
- [ADR 0012 — Frontend dependency management: pnpm](../decisions/0012-frontend-dependency-management-pnpm.md)
- [ADR 0013 — Backend structure: feature packages with Ktor DI](../decisions/0013-backend-feature-packages-kotlin-di.md)
- [ADR 0014 — Build tooling](../decisions/0014-build-tooling-gradle-catalog-no-wrapper.md), flake-scope part superseded by [ADR 0016 — App wrapper in item 02](../decisions/0016-flake-scope-correction-app-wrapper.md)
- [ADR 0015 — Code formatting: ktlint and prettier](../decisions/0015-code-formatting-ktlint-prettier.md) (user requirement added mid-item: `gradle check`/`pnpm test` enforce formatting)
- Conventions: [backend](../conventions/backend.md), [frontend](../conventions/frontend.md)

## Proposal (approved on 2026-10-05)

- `backend/`: Gradle Kotlin DSL, single module; Ktor 3.6 with the Netty engine, kotlinx.serialization, Kotest; CI-less plain Gradle (no wrapper, ADR 0014).
  - `GET /api/health` → `{"status":"ok"}`.
  - Static files served from directory `STT_STATIC_DIR`, with SPA fallback to `index.html` for non-`/api` paths; unknown `/api/...` paths return 404, never `index.html`.
  - `STT_STATIC_DIR` unset → static serving disabled (non-`/api` paths 404), `/api/health` unaffected, startup logs a warning.
- `frontend/`: Vite + React + TypeScript (strict) + styled-components; Vitest + React Testing Library; dependencies via pnpm (ADR 0012); the Vite dev server proxies `/api` to the backend. `pnpm-workspace.yaml` exempts `@vitejs/plugin-react@6.1.2` from pnpm's minimum-release-age policy: the version (published 2026-10-05) is younger than pnpm 11's 10-day cutoff, and without the exemption `pnpm install --frozen-lockfile` fails with `ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION` (verified with pnpm 11.25.0). Drop the exemption at the next lockfile refresh after ~2026-10-15, once the version clears the cutoff.
- Wiring via the Ktor DI plugin (`ktor-server-di`, ADR 0013): decided and recorded, but not wired in item 02 — the skeleton's single health route uses plain parameter wiring. Item 03 (database schema) is the first item that wires the plugin (first real service: the database connection).
- Configuration via env vars prefixed `STT_`:
  - `STT_PORT` (default 8080, valid range 1–65535), `STT_DATA_DIR`, `STT_STATIC_DIR`, `STT_WHISPER_CLI`, `STT_WHISPER_MODEL`, `STT_SPEAKER_MODEL`, `STT_FFMPEG`, `STT_DEVICE`, `STT_THREADS`.
  - Invalid values fail fast at startup with an error naming the variable and the bad value (no silent default); an out-of-range `STT_PORT` fails at config load, not later inside the server.
- Minimal `flake.nix` that builds both parts (backend via `gradle.fetchDeps`, frontend via `fetchPnpmDeps`); finished in item 16.
- Append build-output ignores to `.gitignore`: `build/`, `.gradle/`, `node_modules/`, `dist/`, `result`.
- Write `docs/conventions/backend.md` and `docs/conventions/frontend.md` (approved 2026-10-05): package structure, naming, error handling, test style, component/file layout, theming.
- Replace the `AGENTS.md` Commands placeholder with the real commands (build, run, unit tests, `Integration`-tagged tests, frontend dev server and tests), and add the exact test commands to the Testing section.

## Decisions (consultation 2026-10-05)

| # | Question | Decision | Recorded in |
| -- | -------- | -------- | ----------- |
| 1 | Backend package structure | By feature (`stt/{config,http,queue,transcribe,audio,diarization,outputs,db}`) | ADR 0013, conventions |
| 2 | Kotest spec style | `BehaviorSpec` (given/when/then) | conventions (ADR 0006 defers there) |
| 3 | Gradle version catalog (`gradle/libs.versions.toml`) | Yes | ADR 0014 |
| 4 | Frontend package manager | pnpm | ADR 0012 |
| 5 | Ktor dependency wiring | Ktor DI plugin (`ktor-server-di`) | ADR 0013 |
| 6 | Gradle wrapper vs devenv Gradle | No wrapper; devenv Gradle in dev, nixpkgs gradle in the Nix build | ADR 0014 |
| 7 | Minimal flake scope | Builds both parts now (`nix build .`); the app wrapper (`bin/speech-to-text-app`, java entry point via makeWrapper) ships in item 02 (user request, 2026-10-05); full `STT_*` env wiring, model assets, and `nix run .` finish in item 16 | ADR 0014 as amended by ADR 0016 |

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-05. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] `GET /api/health` responds 200 with body exactly `{"status":"ok"}` (Ktor `testApplication`)
- [x] A request for an existing file under `STT_STATIC_DIR` returns that file with its content
- [x] A request for an unknown non-`/api` path returns `index.html` (SPA fallback)
- [x] A request for an unknown `/api/...` path returns 404, not `index.html`
- [x] With `STT_STATIC_DIR` unset, `/api/health` still responds 200 and an unknown non-`/api` path returns 404 (static serving disabled)
- [x] `STT_PORT` unset → port defaults to 8080 (config unit test)
- [x] `STT_PORT` set to a non-numeric value → startup fails with a clear error, no silent fallback to the default (config unit test)
- [x] (discovered, confirmed 2026-10-05) `STT_PORT` set to a non-numeric value → the error names `STT_PORT` and shows the bad value (config unit test; strengthens the item above's assertion to the new error contract, failing-case behavior unchanged)
- [x] (discovered, confirmed 2026-10-05) `STT_PORT` outside 1–65535 (0, 70000) → fails at config load with a clear error; endpoints 1 and 65535 load fine (config unit test)
- [x] (discovered, confirmed 2026-10-05) With static serving on, a path merely starting with `/api` (e.g. `/apiary`) gets the SPA fallback, not 404 (http test)
- [x] (discovered, confirmed 2026-10-05) The exact path `/api` returns 404, never `index.html` (http test; pins the boundary while the fix above lands)
- [x] (discovered, confirmed 2026-10-05) With `STT_STATIC_DIR` unset, startup logs a warning that static serving is disabled (boot test)
- [x] The frontend root renders (one Vitest + RTL render test) — green in the user's devenv shell (`pnpm test`) and in-sandbox via node

## Acceptance criteria

- `backend/` and `frontend/` build and their test suites pass inside `devenv shell` using the commands written into `AGENTS.md` (frontend via pnpm).
- Running the backend with `STT_STATIC_DIR` pointing at the built frontend serves the UI at `http://localhost:8080` and `GET /api/health` returns `{"status":"ok"}`.
- User runs `nix build .` on the minimal `flake.nix` successfully (both parts; `deps.json` for `gradle.fetchDeps` and the `fetchPnpmDeps` store hash are generated by the user; cannot be verified inside the agent sandbox).
- `.gitignore` contains `build/`, `.gradle/`, `.kotlin/`, `node_modules/`, `dist/`, `result`; `git status` is clean after a full build.
- `docs/conventions/backend.md` and `docs/conventions/frontend.md` exist and were approved by the user.
- `AGENTS.md` Commands section lists the real commands.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-05: consultation decisions 1–7 approved as presented; `STT_STATIC_DIR`-unset behavior (static disabled, 404 for non-`/api`, health unaffected) approved; test list of 8 items approved; `docs/conventions/backend.md` and `docs/conventions/frontend.md` drafts approved; ADRs 0012–0014 approved. User allowed an ephemeral sandbox `pnpm` install (session-only, for agent verification; the real provision is the committed `pkgs.pnpm` in devenv.nix).
- 2026-10-05: user added a mid-item requirement: automatic code formatting with enforcement (ktlint + prettier, ADR 0015).
- 2026-10-05: user requested the built artifact expose `bin/speech-to-text-app`; implemented as the flake `default` package (merged frontend + backend distribution + makeWrapper java wrapper).
- 2026-10-05: user-verified acceptance runs: `pnpm test`/`pnpm build` green; `nix build .` green (both parts); `./result/bin/speech-to-text-app` served `{"status":"ok"}`, `/` 200, SPA fallback 200, `/api/nope` 404.
- 2026-10-05 (review fixes): implement the `STT_STATIC_DIR`-unset startup warning with a (discovered) test item; `STT_PORT` errors fail fast at config load naming the variable and value, valid range 1–65535 (two (discovered) items); the `/api` boundary excludes exactly `/api` and `/api/...` — `/apiary` gets the SPA fallback, exact `/api` 404s (two (discovered) items); spec decision table points the Kotest style at conventions; ADR 0014's flake-scope part is superseded by ADR 0016 (which records the wrapper's CLI-argument behavior); the Nix build runs `gradleCheckTask = "check"`; the first Ktor DI wiring is item 03 (the database connection).
- 2026-10-05 (review fixes, decision 9 revisited): removal of the `@vitejs/plugin-react@6.1.2` exemption in `pnpm-workspace.yaml` was verified in-sandbox to break `pnpm install --frozen-lockfile` (`ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`, pnpm 11.25.0 supply-chain policy). The user chose to keep the exemption; the reason and drop date are recorded in the Proposal.
- 2026-10-06 (second review fixes): user's post-fix `nix build .` succeeded from branch head b718f7a (result built 00:04, after the last commit at 23:53) — the Nix check with `gradleCheckTask = "check"` passed. Reviewer then ran `./result/bin/speech-to-text-app` and probed: `/api/health` → 200 `{"status":"ok"}`; `/`, `/some/route`, `/apiary` → 200 index.html; `/api`, `/api/nope` → 404; `/lib/config-1.4.9.jar` and `/bin/speech-to-text-app` → 200 index.html (the jar and wrapper are no longer served); log level INFO. Reviewer suites: `gradle clean check` 11/11 green, vitest 1/1, prettier/tsc/vite build green. Leftovers resolved: `.kotlin/` ignored (clean `git status` after `gradle clean check`), missing final newlines added, MainSpec logger level restored (approved 2026-10-06).
- 2026-10-06: user approved the diff; fast-forward merged into `main` (head 97773fe). A stray blank line appended to `main`'s copy of the roadmap file during the leftover session (uncommitted, no content) was discarded before the merge.
