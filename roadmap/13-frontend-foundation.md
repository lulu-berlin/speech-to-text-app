# 13 — Frontend foundation

Status: draft
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done), [12](12-http-api-and-sse.md)

## Goal
Provide the shared frontend building blocks that the Queue (item 14) and History (item 15) views build on: a typed API client, a live-update hook over SSE, theming, the app shell, and test infrastructure for mocking the API.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components, served by the backend.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): live state via `GET /api/events`, all commands via REST.
- [ADR 0009 — Approval-gated Canon TDD workflow](../docs/decisions/0009-approval-gated-canon-tdd-workflow.md).

## Proposal (pending approval)
- Typed API client covering the item 12 endpoints (tasks, queue, transcript download, audio URL), with request/response types mirroring the backend JSON and the error shape `{ "error": "<code>", "message": "..." }` surfaced as a typed error.
- `useEvents` hook subscribing to `GET /api/events` (`task-updated`, `task-progress`, `queue-updated`) via `EventSource`, with reconnect after connection loss and re-fetch of current state on reconnect so no update is missed.
- styled-components theme (colors, spacing, typography) + global styles, provided at the app root.
- App shell with navigation between two views: Queue and History (content filled in by items 14 and 15).
- MSW handlers for the API and a test helper that renders components inside the theme/providers, used by all frontend tests.

## Open questions for the user
- Routing library: react-router vs none (two views switched by local state)?
- State approach: React context + reducer fed by SSE vs TanStack Query (with SSE events invalidating/patching queries)?
- Visual direction (look and feel, light/dark theme, density)?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] The API client returns typed task data for a successful `GET /api/tasks`
- [ ] The API client turns a 4xx `{ error, message }` response into a typed error carrying code and message
- [ ] `useEvents` delivers a `task-updated` event to its subscriber
- [ ] `useEvents` reconnects after the event stream drops and state is refreshed after reconnecting
- [ ] The app shell renders the Queue view by default
- [ ] Navigating to History shows the History view
- [ ] Components render with the theme available (themed values applied)

## Acceptance criteria
- Frontend tests (Vitest + React Testing Library + MSW) for the client, `useEvents`, and app shell pass.
- Running the backend and frontend together, the shell loads and switches between Queue and History; the browser network panel shows a live `/api/events` stream that reconnects after a backend restart.
- Chosen routing and state approaches recorded (ADR if they are decisions with lasting impact).
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
