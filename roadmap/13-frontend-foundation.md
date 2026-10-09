# 13 — Frontend foundation

Status: in-progress
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done), [12](12-http-api-and-sse.md) (spec-approved only: its contract, not its implementation)

## Goal
Provide the shared frontend building blocks that the Queue (item 14) and History (item 15) views build on:
- a typed API client;
- state machines for the live connection and for commands;
- routing;
- light and dark theming;
- the app shell;
- a shared confirmation dialog;
- MSW-based mocking for tests and for a backend-free dev mode.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components, served by the backend.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): live state via `GET /api/events`, all commands via REST.
- [ADR 0009 — Approval-gated Canon TDD workflow](../docs/decisions/0009-approval-gated-canon-tdd-workflow.md).
- [ADR 0029 — Contract-first frontend](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md): the frontend builds against item 12's approved contract with MSW mocks, in parallel with items 09–12. The files in [`docs/api/`](../docs/api/) are the only source of mock data. The check against the running backend is item [21](21-frontend-backend-end-to-end.md).
- [ADR 0031 — Frontend routing with react-router, state with XState](../docs/decisions/0031-frontend-routing-react-router-state-xstate.md): react-router URLs; XState v5 + `@xstate/react` machines for client actions, with server state applied as received; one transition table, `docs/api/task-transitions.json`.
- [Frontend conventions](../docs/conventions/frontend.md): `app/`, `features/<feature>/`, `shared/`; design values only as theme tokens; named exports; colocated tests; typed API client in `src/shared/api/`.

## Proposal (approved 2026-10-09)
- **Dependencies** (each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash; `nix build .` is verified by the user, ADR 0012):
  - runtime: `react-router`, `xstate`, `@xstate/react`;
  - dev: `msw` (>= 2.12, which has the `sse()` API for Server-Sent Events).
- **Typed API client** (`src/shared/api/`):
  - covers every item 12 endpoint: upload, tasks, task commands (pause, resume, cancel, retry), delete, queue, turns, transcript download URL, audio URL;
  - its types mirror item 12's contract;
  - an error body `{ error, message }` (plus `taskId` for `duplicate-audio`) becomes a typed error carrying the code.
- **State machines** (`src/shared/state/`, XState v5):
  - **Connection machine:** opens `GET /api/events` and goes connecting → live on the first `snapshot`, which replaces tasks and queue. `task-updated`, `task-progress`, `queue-updated` and `task-deleted` then update its context. When the stream drops it goes to reconnecting, and the next `snapshot` replaces the state again. Server updates are applied as received; a task update that breaks the transition table is logged, not rejected.
  - **Command machine** (one per command invocation): idle → sending → done or failed.
    - It only starts when `docs/api/task-transitions.json` allows the command for the task's current status, so no request is sent otherwise.
    - On done, the returned task updates the store.
    - On failed, the typed error is exposed to the view (inline error, frontend conventions).
  - **Allowed commands per status:** read from `docs/api/task-transitions.json`, which item 14 uses to show only allowed controls.
  - React access through `@xstate/react` hooks.
- **Routing** (react-router):
  - `/` redirects to `/queue`;
  - `/queue` shows the Queue view, `/history` the History view, `/history/:taskId` a task's transcript (contents from items 14 and 15);
  - any other path shows a not-found view.
- **Theme:**
  - light and dark token sets in `src/shared/theme/theme.ts`, chosen by `prefers-color-scheme` and following live changes;
  - global styles;
  - calm, document-like look: generous spacing, readable typography (the transcript is the center), the queue as a clean list.
- **App shell:** a header with the product name "Speech-to-Text" (item 17 adds the stenographer animation there), navigation between Queue and History, and a connection indicator (live or reconnecting).
- **UI language:** English only, as plain strings, with no i18n layer.
- **Confirmation dialog** (`src/shared/`): built on the native `<dialog>` element, styled with tokens; confirm runs the action, cancel and Escape do not. Used by items 14 and 15 (cancel, delete).
- **MSW:**
  - handlers for every endpoint and for `/api/events` (`sse()`), serving only `docs/api/` files;
  - a test helper that renders inside providers (theme, router, machines);
  - **`pnpm dev:mock`** runs the same handlers in the browser (MSW service worker). The mocked stream sends `snapshot.json`, then scripted events. Dev only, never part of `pnpm build`'s output.
- **Risk to settle in the first cycle:** jsdom has no `EventSource`. Tests need a polyfill, or the connection machine takes an injectable `EventSource` factory. The workspace agent proposes one at the first checkpoint.

## Open questions (answered 2026-10-09)
- Routing library? → **react-router** (ADR 0031).
- State approach? → **XState v5 + `@xstate/react`** for client actions; server state applied as received (ADR 0031).
- Transition table source? → **`docs/api/task-transitions.json`**, contract data shared with the backend check (item 11).
- Light/dark? → **Both, following the OS.**
- Look and feel? → **Calm, document-like.**
- UI language? → **English only.**
- Run without a backend? → **Yes, `pnpm dev:mock`** with the same MSW handlers.
- Dialogs? → **Native `<dialog>`.**

## Test list (written with the user; Canon TDD step 1)
Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.
- [x] The API client returns typed tasks for `GET /api/tasks` (served from `docs/api/task-list.json`)
- [x] The API client turns an error response into a typed error with code and message, and `duplicate-audio` with its `taskId`
- [x] The connection machine goes from connecting to live on the first `snapshot`, which replaces tasks and queue
- [x] `task-updated`, `task-progress`, `queue-updated` and `task-deleted` events update the state
- [x] When the stream drops, the connection machine goes to reconnecting, and the next `snapshot` replaces the state
- [x] A task update that breaks the transition table is applied and logged
- [x] A command not allowed for the task's status (per `docs/api/task-transitions.json`) cannot be started and sends no request
- [x] A command goes sending → done and updates the task from the response, or sending → failed and exposes the typed error
- [x] `/` redirects to `/queue`; `/history` and `/history/:taskId` show their views; an unknown path shows the not-found view
- [x] The header navigation switches between Queue and History and shows whether the connection is live
- [ ] Dark tokens apply when the OS prefers dark, light tokens otherwise
- [ ] The confirmation dialog runs the action on confirm and not on cancel or Escape
- [ ] Components render with the theme available (themed values applied)
- [ ] (discovered, confirmed 2026-10-09) A `task-updated` for a task the state does not hold yet (e.g. a new upload) adds it
- [ ] (discovered, confirmed 2026-10-09) When the server answers a reconnect with an HTTP error, so `EventSource` stops retrying, the connection machine reconnects by itself

## Acceptance criteria
- Frontend tests (Vitest + React Testing Library + MSW) for the client, the machines, routing, the shell, theming and the dialog pass.
- `pnpm dev:mock` serves the app in the browser without a backend, live from the mocked stream, and the production build (`pnpm build`) contains no MSW code or worker (user-verified in the browser).
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependencies.
- Running against the real backend is checked in item 21.
- All test-list items ticked and green; user approved the diff.

## Mock additions to settle with item 12
The user allowed additions to the mocks (2026-10-09) if each states why, so it can be settled with the backend (item 12's route tests, item 21's end-to-end check). Each is marked "Mock addition" in `frontend/src/shared/api/`.
- **New examples `docs/api/task.paused.json` (id 7) and `docs/api/task.canceled.json` (id 5).** No example had status PAUSED or CANCELED, which the pause and cancel commands return. Listed in `docs/api/README.md`'s Shapes table; item 12's route tests should check them like the other Task examples.
- **Task command responses** (`POST /api/tasks/{id}/pause|resume|cancel|retry`): the Task example whose status is the command's `to` in `task-transitions.json`, with the URL's id. The examples hold one Task per status, not one per (task, command) pair, so other fields (filename, progress, `finishedAt`) belong to the example, not to the requested task. The backend returns the real task.
- **`POST /api/queue/pause|resume`**: `queue.json` with `paused` set to `true`/`false`. `queue.json` has `paused: false`, and a pause response must show the paused queue.
- **`PUT /api/queue/order`**: `queue.json` with the request's `taskIds`. The response is the queue in its new order; the mock does not check the set (the backend answers `409 reorder-mismatch`).
- **Test stream for the live events** (`connectionMachine.test.ts`): after `snapshot.json`, `task-updated` carries `task.paused.json` (id 7, QUEUED in the snapshot) and `queue-updated` carries `queue.json` with `paused: true`. The stock examples (`task.completed.json` for id 3, `queue.json`) equal what `snapshot.json` already holds, so applying them would show no change. Only the test uses this stream; the default stream sends just the snapshot.
- **Test stream for a drop and reconnect** (`connectionMachine.test.ts`): the first connection sends an SSE `retry: 50` field, then `snapshot.json`, then ends; the second sends `snapshot.json` without task 4. The `retry` hint keeps the test from waiting for EventSource's default reconnect delay (the contract does not say whether the backend sends `retry`). The reduced snapshot stands for a task deleted while the client was disconnected; without it the second snapshot would equal the first and replacing the state would show no change.
- **Test stream for an illegal transition** (`connectionMachine.test.ts`): `task-updated` carries `task.running.json` with id 3. The example's own id is 5, which is already RUNNING in the snapshot, so no status change; id 3 (COMPLETED in the snapshot) makes RUNNING an illegal transition.
- **Failed command** (`commandMachine.test.ts`): the cancel handler is overridden to answer `409` with `error.json`. `docs/api/` has no example for `409 illegal-transition` (or any `409` but `duplicate-audio`), so `error.json` stands in; its `error` code is `not-found`.

Known differences from the contract, no example to serve:
- `GET /api/tasks/{id}/turns` returns turns for any known task (by its `speakersMode`), not `409 not-completed` for a task that is not COMPLETED: `error.json`'s code is `not-found`, and there is no `not-completed` example.
- `DELETE /api/tasks/{id}` answers `204` for any id, also unknown ones (the backend answers `404`).
- The `404` for an unknown task id serves `error.json` verbatim, so its message names task 42 whatever the id.
- `POST /api/tasks` always returns `task.queued.json` (id 7), which already exists in `snapshot.json`.

## Approval log
- 2026-10-09: consultation answers approved: react-router; XState v5 + `@xstate/react` for client actions with server state applied as received ([ADR 0031](../docs/decisions/0031-frontend-routing-react-router-state-xstate.md)); transition table shared as `docs/api/task-transitions.json`; light and dark following the OS; calm, document-like look; English only; `pnpm dev:mock`; native `<dialog>`. Test list not yet approved.
- 2026-10-09: test list (13 items) approved by the user; status `spec-approved`.
- 2026-10-09 (workspace, cycle 1): added `docs/api/task.paused.json` and `docs/api/task.canceled.json` (PAUSED/CANCELED had no example), `allowBuilds: msw` in `pnpm-workspace.yaml`, then `Test: API client returns typed tasks for GET /api/tasks`. Installed `react-router@8.4.0`, `xstate@5.33.2`, `@xstate/react@6.1.0`, `msw@2.15.0`, `eventsource@5.1.2` (dev; jsdom has no `EventSource`). MSW handlers + fixtures re-export serve only `docs/api/` (ADR 0029); `docs/api/` is imported via `resolveJsonModule` and the dev server reaches it via `server.fs.allow: ['..']`. Red: the suite failed to load (`Invariant Violation: Failed to construct a Server-Sent Event handler … the EventSource API is not supported`); the SSE stream moved to a lazy raw `text/event-stream` handler (`events.ts`, MSW's `sse()` cannot type named events). Green: 2 files / 2 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09: `fetchPnpmDeps` hash renewed for the new dependencies (`got:` value from the user's `nix build .`); the user's `nix build .` then succeeded.
- 2026-10-09 (workspace, cycle 2): `Test: API client turns error responses into typed errors, duplicate-audio with taskId`. Red: `expected { id: 7, filename: 'memo.wav', … } to be an instance of ApiError` (the client threw plain errors / returned the 201 body). Green: `ApiError` (code, message, `taskId`), `getTask`, multipart `uploadTask`; the 404 comes from the default `GET /api/tasks/:id` handler (`error.json`), the 409 from a per-test override serving `error.duplicate-audio.json` (shared `src/test/server.ts`). 2 files / 4 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09: cycle 2 refactors approved by the user (all five), each its own commit: one typed list of Task examples (`taskExamples`), `Task` types imported from `./types`, command handlers derived from `task-transitions.json`, the client's error body typed as `ApiErrorBody`, typed-error tests with `rejects` matchers. 2 files / 4 tests green after each.
- 2026-10-09 (workspace, cycle 3): `Test: connection machine goes from connecting to live on the first snapshot`. Red: `Failed to resolve import "./connectionMachine"`. Green: `connectionMachine` (XState v5, `src/shared/state/`) invokes the client's new `subscribeToEvents` (the only place that opens `EventSource`) and assigns the snapshot's tasks and queue on entering `live`; the SSE handler is now a plain `http.get` in the default handlers (`events.ts`), no longer lazy; `Queue`, `Snapshot`, `ServerEvent` types. Expected values are `docs/api/snapshot.json`. 3 files / 5 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09: cycle 3 refactor approved: `eventsHandler` moved from `events.ts` into `handlers.ts` (`events.ts` deleted). 3 files / 5 tests green.
- 2026-10-09 (workspace, cycle 4): `Test: task-updated, task-progress, queue-updated and task-deleted events update the state`. A first run passed the wait too early (the empty initial state has no task 4), so the wait now requires `live`; then the honest red: `Error: Timeout of 2000 ms exceeded` (the machine ignored the four events). Green: `ServerEvent` covers all five events, the client's `subscribeToEvents` listens to each, the machine's `live` state replaces a task on `task-updated`, sets both progress fields on `task-progress`, replaces the queue on `queue-updated`, removes the task on `task-deleted`; `eventStreamResponse` in `handlers.ts` builds a stream from named events. Expected values derived from `snapshot.json`, `task.paused.json`, `task-progress.json`, `queue.json`, `task-deleted.json`. 3 files / 6 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean. Discovered: `task-updated` for an unknown task (pending confirmation).
- 2026-10-09: discovered scenarios confirmed by the user: a `task-updated` for an unknown task adds it; reconnecting by itself when the server answers a reconnect with an HTTP error.
- 2026-10-09 (workspace, cycle 5): `Test: when the stream drops the connection machine reconnects and the next snapshot replaces the state`. Red: `Error: Timeout of 2000 ms exceeded` (the machine stayed `live` with the first snapshot). Green: the client reports EventSource's `error` as a `disconnected` stream event (`StreamEvent`), `live` goes to `reconnecting` on it, and a `snapshot` in any state replaces tasks and queue and goes to `live` (root-level transition). `eventStreamResponse` takes `retryMs` and `end`. The test checks the visited states (`connecting`, `live`, `reconnecting`, `live`) and the second snapshot's tasks and queue. 3 files / 7 tests, stable over 3 more runs; `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09 (workspace, cycle 6): `Test: a task update outside the transition table is applied and logged`. Red: `expected "warn" to be called 1 times, but got 0 times` (the update was applied but never logged). Green: `src/shared/state/transitions.ts` reads `transitions` from `docs/api/task-transitions.json` (`isAllowedTransition`), the machine's `task-updated` action warns via `console.warn` when the previous status does not allow the new one, and still applies the update. Mock addition: the test sends `task.running.json` with id 3 (the example's id 5 is already RUNNING in the snapshot). 3 files / 8 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09 (workspace, cycle 7): `Test: a command the task status does not allow cannot start and sends no request`. Red (guard absent): `expected 'sending' to be 'idle'`, so a disallowed command started. Green: `commandMachine` (`src/shared/state/commandMachine.ts`, input `{ command, taskId, status }`) starts only when `isAllowedCommand` (in `transitions.ts`, reading `commands` from `docs/api/task-transitions.json`) allows the command for the status; `fetch` spy asserts no request. The `sending` state is filled in cycle 8. 4 files / 9 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09 (workspace, cycle 8): `Test: a command goes sending to done with the response task or to failed with the typed error`. Red: `Error: Timeout of 2000 ms exceeded` (`sending` never reached `done`). Green: `sending` invokes a promise actor calling the client's new `runTaskCommand` (`POST /api/tasks/{id}/{command}`), `onDone` assigns the response task and goes to `done`, `onError` assigns the error and goes to `failed`. Mock addition: the cancel handler is overridden with `error.json` at `409`, because `docs/api/` has no example for `409 illegal-transition`. 4 files / 10 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09 (workspace, cycle 9): `Test: routing redirects / to /queue and routes history, transcript and not-found`. Red: `Unable to find an accessible element with the role "heading" and name "Queue"`. Green: `App` renders routes (`/` -> `/queue` via `Navigate`, `/queue`, `/history`, `/history/:taskId`, `*` -> `NotFoundView`); views are placeholders under `src/features/queue`, `src/features/history` and `src/app`; shared `PageHeading`/`Text` in `src/shared/ui/typography.tsx` use theme tokens; `main.tsx` wraps `App` in `BrowserRouter`. The existing item 02 title test now renders inside `MemoryRouter`. 4 files / 11 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09 (workspace, cycle 10): `Test: the header switches between Queue and History and shows whether the connection is live`. Red: `Unable to find an accessible element with the role "link" and name "History"`. Green: `App` renders `ConnectionContext` (`createActorContext(connectionMachine)` from `@xstate/react`, `src/app/ConnectionContext.tsx`), so one stream serves the app; `AppHeader` (`src/app/AppHeader.tsx`) holds the product name, `NavLink`s to Queue and History, and an indicator reading the machine state (`Connecting`/`Live`/`Reconnecting`). New `colors.border` token. 4 files / 12 tests, `tsc --noEmit`, `vite build`, prettier `--check` clean.
