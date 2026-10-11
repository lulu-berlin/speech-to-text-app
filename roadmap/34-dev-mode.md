# 34 — Dev mode: frontend and backend with one command

Status: in-progress
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

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. devenv-level items are user-verified (ADR 0010):

- [x] `devenv up` starts the frontend dev server and the backend; the UI at the printed Vite URL loads the queue through the `/api` proxy
      Scenario (user-run): in `workspaces/34-dev-mode`, `direnv allow` (fresh worktree state), free ports 5173/8080, then `devenv up`. Expect: `frontend` runs `pnpm dev` and prints `Local: http://localhost:5173/` (no browser opens); `backend` runs the dev runner — `gradle installDist` then the installed server (Ktor on 8080, `STT_DATA_DIR` from the shell; restarts on source changes). Open `http://localhost:5173/`: the queue page renders the live state, and the network log shows `/api/events` (SSE) answered 200 through the proxy (backend log shows the connection). Stop with Ctrl-C / `devenv down`.
- [x] Editing a frontend component updates the open page through hot module reload, without a full page reload
      Scenario (user-run): with `devenv up` running and `http://localhost:5173/` open on the queue page, edit `frontend/src/app/AppHeader.tsx` — change `<Title>Speech-to-Text</Title>` to `Speech-to-Text (dev)` and save. Expect: the open page shows the new heading within ~1s without a full page reload (DevTools Network shows no new `document`/`index.html` request; the Live indicator and the SSE connection stay up). Revert the edit: the heading returns to `Speech-to-Text`.
- [x] Editing backend source rebuilds and restarts the backend; the next request is answered by the changed code, and the open page reconnects its live updates
      Scenario (user-run): with `devenv up` running (backend process = the dev runner) and the queue page open, add a temporary `bootLogger.info("dev-restart marker")` line to `main()` in `backend/src/main/kotlin/stt/Main.kt` and save. Expect: the backend log shows "change detected; rebuilding", a restart, and the marker line after startup; `curl http://localhost:8080/api/queue` answers 200 from the new build; the open page shows a brief "Reconnecting…" then returns to Live. Revert the edit.
- [x] A backend compile error is shown in the backend's log and the last working backend keeps running; fixing the error restarts it
      Scenario (user-run): with `devenv up` running, add `val broken = noSuchThing` to `main()` in `backend/src/main/kotlin/stt/Main.kt` and save. Expect: the backend log shows the Gradle compile error and "build failed — keeping the last working server"; `curl http://localhost:8080/api/queue` still answers 200 and the page stays Live (no restart). Revert the line: expect a rebuild and a restart ("starting build/install/stt-backend/bin/stt-backend" in the log) and the page reconnects.
- [x] A task running when the backend restarts resumes after the restart
      Scenario (user-run): with `devenv up` running, start a transcription task that is still RUNNING when the restart lands (a longer audio file, or fire the restart right after the task starts — if it completes too fast, retry with an immediate restart). While it is RUNNING, force a backend restart by editing a backend source file (e.g. the item-3 marker line) and saving. Expect: the page blips "Reconnecting…" then returns to Live; the task is still there and resumes — its status stays RUNNING and the progress keeps advancing — and it eventually reaches COMPLETED (no FAILED, no stuck); the backend log shows the recovery. Revert the edit.
- [x] Dev data lives in a fixed, git-ignored directory and survives `devenv up` restarts
      Scenario (user-run): with `devenv up` running, `ls .devenv/state/stt/` (worktree root) shows `stt.db`; `git check-ignore .devenv/state/stt/stt.db` matches (and `git status` stays clean). Stop everything (Ctrl-C then `q`), then `devenv up` again. Expect: the page loads, History still shows the tasks completed in earlier runs, and queue/paused state survives.
- [ ] Stopping `devenv up` stops both halves and leaves no whisper-cli or ffmpeg processes behind
- [ ] (must not break) `gradle check`, `pnpm test`, `gradle run` and `pnpm dev` work as before
- [x] (discovered) The `devenv up` output shows a stop hint: the frontend's first printed line is "Stop: press Ctrl-C then q (confirm) to quit devenv and stop both processes (or run `devenv down`)"; pressing Ctrl-C then q stops both
      Scenario (user-run): in `workspaces/34-dev-mode` with the hint in place, `devenv up`. Expect: the frontend process's first log line (under the `frontend` label in the devenv TUI) is the stop hint. Press Ctrl-C then `q`: devenv exits and both processes stop — no guessing about how to quit. (Discovered 2026-10-11: the running TUI gave no stop hint and the user didn't know how to stop it; confirmed with the user to print one; wording corrected after the user found `q` alone does nothing — Ctrl-C first, then q confirms.)

## Acceptance criteria

- User-run: `devenv up` starts both halves; the UI works through the Vite URL; editing a frontend component updates the page without reload; editing backend code is picked up without a manual restart.
- AGENTS.md documents the command; the README (item 35) mentions it.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted from the user's request; answered so far: `devenv up` here, `./gradlew dev` in item 33.
- 2026-10-11: consultation answers: restart on change (no Ktor auto-reload); running tasks resume through restart recovery; no browser opening. Proposed test list (8 items). Remaining: test list approval.
- 2026-10-11: test list approved by the user; status `spec-approved`.
