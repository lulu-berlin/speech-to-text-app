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

## Open questions (answered 2026-10-11)

- Backend reload? → **Restart on change**: a continuous build and a backend restart when the build output changes (seconds per change); no Ktor auto-reload.
- A task running during a dev restart? → resumes through item 11's restart recovery, as in production.
- Open the browser? → **No**; `devenv up` prints the URL.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Proposed 2026-10-11, to be approved. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. devenv-level items are user-verified (ADR 0010):

- [ ] `devenv up` starts the frontend dev server and the backend; the UI at the printed Vite URL loads the queue through the `/api` proxy
- [ ] Editing a frontend component updates the open page through hot module reload, without a full page reload
- [ ] Editing backend source rebuilds and restarts the backend; the next request is answered by the changed code, and the open page reconnects its live updates
- [ ] A backend compile error is shown in the backend's log and the last working backend keeps running; fixing the error restarts it
- [ ] A task running when the backend restarts resumes after the restart
- [ ] Dev data lives in a fixed, git-ignored directory and survives `devenv up` restarts
- [ ] Stopping `devenv up` stops both halves and leaves no whisper-cli or ffmpeg processes behind
- [ ] (must not break) `gradle check`, `pnpm test`, `gradle run` and `pnpm dev` work as before

## Acceptance criteria

- User-run: `devenv up` starts both halves; the UI works through the Vite URL; editing a frontend component updates the page without reload; editing backend code is picked up without a manual restart.
- AGENTS.md documents the command; the README (item 35) mentions it.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted from the user's request; answered so far: `devenv up` here, `./gradlew dev` in item 33.
- 2026-10-11: consultation answers: restart on change (no Ktor auto-reload); running tasks resume through restart recovery; no browser opening. Proposed test list (8 items). Remaining: test list approval.
