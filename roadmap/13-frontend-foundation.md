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
- [ ] The API client returns typed tasks for `GET /api/tasks` (served from `docs/api/task-list.json`)
- [ ] The API client turns an error response into a typed error with code and message, and `duplicate-audio` with its `taskId`
- [ ] The connection machine goes from connecting to live on the first `snapshot`, which replaces tasks and queue
- [ ] `task-updated`, `task-progress`, `queue-updated` and `task-deleted` events update the state
- [ ] When the stream drops, the connection machine goes to reconnecting, and the next `snapshot` replaces the state
- [ ] A task update that breaks the transition table is applied and logged
- [ ] A command not allowed for the task's status (per `docs/api/task-transitions.json`) cannot be started and sends no request
- [ ] A command goes sending → done and updates the task from the response, or sending → failed and exposes the typed error
- [ ] `/` redirects to `/queue`; `/history` and `/history/:taskId` show their views; an unknown path shows the not-found view
- [ ] The header navigation switches between Queue and History and shows whether the connection is live
- [ ] Dark tokens apply when the OS prefers dark, light tokens otherwise
- [ ] The confirmation dialog runs the action on confirm and not on cancel or Escape
- [ ] Components render with the theme available (themed values applied)

## Acceptance criteria
- Frontend tests (Vitest + React Testing Library + MSW) for the client, the machines, routing, the shell, theming and the dialog pass.
- `pnpm dev:mock` serves the app in the browser without a backend, live from the mocked stream, and the production build (`pnpm build`) contains no MSW code or worker (user-verified in the browser).
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependencies.
- Running against the real backend is checked in item 21.
- All test-list items ticked and green; user approved the diff.

## Approval log
- 2026-10-09: consultation answers approved: react-router; XState v5 + `@xstate/react` for client actions with server state applied as received ([ADR 0031](../docs/decisions/0031-frontend-routing-react-router-state-xstate.md)); transition table shared as `docs/api/task-transitions.json`; light and dark following the OS; calm, document-like look; English only; `pnpm dev:mock`; native `<dialog>`. Test list not yet approved.
- 2026-10-09: test list (13 items) approved by the user; status `spec-approved`.
