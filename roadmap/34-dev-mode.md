# 34 — Dev mode: frontend and backend with one command

Status: draft
Depends on: —

## Goal

One command in the devenv shell starts the whole app for development: the Vite dev server with hot module reload and the backend, which picks up code changes without a manual restart.

## Already decided

- User requests (2026-10-11): an easy way to run the whole app in dev mode, frontend and backend; hot reload is a bonus.
- **The command (user, 2026-10-11):** `devenv up` (devenv processes); item 33 adds `./gradlew dev` for users without Nix on top of this item.
- Today: `pnpm dev` (Vite, proxies `/api` to `http://localhost:8080`) and `gradle run` with `STT_DATA_DIR` set, in two terminals (AGENTS.md § Commands).
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): developer tooling, not an app feature: exempt from its own Playwright test.
- [ADR 0010](../docs/decisions/0010-development-toolchain.md): any tool this needs comes from `devenv.nix`.

## Proposal

To be settled in the consultation. Starting points:

- `devenv.nix` `processes`: `frontend` (`pnpm dev`, HMR) and `backend` (Ktor on 8080), both started and stopped by `devenv up`; the UI is opened at the Vite URL.
- Dev data directory: a fixed, ignored location (e.g. `.devenv/state/stt`), so dev data survives restarts and never touches a packaged app's data.
- Backend reload, two candidates:
  - **Restart on change**: a continuous build (`gradle -t`) and a process restart when classes change — a few seconds per change, robust;
  - **Ktor auto-reload** (development mode + watch paths): faster, but reloads the application inside the running JVM, which must stop and restart the engine's `SupervisorJob` scope cleanly (item 26's start/stop race) and has known limits with module wiring.
- AGENTS.md § Commands documents the command.

## Open questions

- Backend reload: restart on change (proposed baseline) or Ktor auto-reload?
- What happens to a task running when the backend restarts in dev mode — rely on item 11's restart recovery (it resumes), as in production?
- Should `devenv up` also open the browser?

## Test list (written with the user; Canon TDD step 1)

To be written in the consultation.

## Acceptance criteria

- User-run: `devenv up` starts both halves; the UI works through the Vite URL; editing a frontend component updates the page without reload; editing backend code is picked up without a manual restart.
- AGENTS.md documents the command; the README (item 35) mentions it.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted from the user's request; answered so far: `devenv up` here, `./gradlew dev` in item 33.
