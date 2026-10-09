# 13 — Frontend foundation

Status: draft
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done), [12](12-http-api-and-sse.md) (spec-approved only: its contract, not its implementation)

## Goal
Provide the shared frontend building blocks that the Queue (item 14) and History (item 15) views build on: a typed API client, a live-update hook over SSE, theming, the app shell, and test infrastructure for mocking the API.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components, served by the backend.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): live state via `GET /api/events`, all commands via REST.
- [ADR 0009 — Approval-gated Canon TDD workflow](../docs/decisions/0009-approval-gated-canon-tdd-workflow.md).
- Contract-first (consultation 2026-10-09): the frontend builds against item 12's approved contract with MSW mocks, in parallel with items 09–12. The example JSON files in [`docs/api/`](../docs/api/) are the only source of mock data; the check against the running backend is item [21](21-frontend-backend-end-to-end.md).

## Proposal (pending approval)
- Typed API client covering the item 12 endpoints (tasks, queue, delete, turns, transcript download, audio URL), with request/response types mirroring item 12's contract and the error shape `{ "error": "<code>", "message": "..." }` (plus `taskId` for `duplicate-audio`) surfaced as a typed error.
- `useEvents` hook subscribing to `GET /api/events` via `EventSource`. Every (re)connect starts with a `snapshot` event that replaces the client state; `task-updated`, `task-progress`, `queue-updated` and `task-deleted` update it afterwards. No refetch after a reconnect (item 12 sends the snapshot).
- styled-components theme (colors, spacing, typography) + global styles, provided at the app root.
- App shell with navigation between two views: Queue and History (content filled in by items 14 and 15).
- MSW handlers for the API serving the `docs/api/` examples, and a test helper that renders components inside the theme/providers, used by all frontend tests.

## Open questions for the user
- Routing library: react-router vs none (two views switched by local state)?
- State approach: React context + reducer fed by SSE vs TanStack Query (with SSE events invalidating/patching queries)?
- Visual direction (look and feel, light/dark theme, density)?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] The API client returns typed task data for a successful `GET /api/tasks`
- [ ] The API client turns a 4xx `{ error, message }` response into a typed error carrying code and message
- [ ] `useEvents` delivers a `task-updated` event to its subscriber
- [ ] `useEvents` replaces the state with the `snapshot` received after the event stream drops and reconnects
- [ ] The app shell renders the Queue view by default
- [ ] Navigating to History shows the History view
- [ ] Components render with the theme available (themed values applied)

## Acceptance criteria
- Frontend tests (Vitest + React Testing Library + MSW) for the client, `useEvents`, and app shell pass.
- Running against the real backend is checked in item 21.
- Chosen routing and state approaches recorded (ADR if they are decisions with lasting impact).
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
