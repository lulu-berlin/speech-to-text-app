# 0031 — Frontend routing with react-router, state with XState

Status: accepted · Date: 2026-10-09

## Context
The frontend (items 13–15) needs navigation between the queue, the history and a single transcript, and it needs client state fed by the SSE stream (item 12's `snapshot` and events) and by REST command responses. The user wants a deterministic state machine that makes illegal transitions impossible (item 13 consultation, 2026-10-09). The backend is the authority on task status (item 04's transition table, amended by item 11 for retry), so the client must not end up disagreeing with it.

## Decision
- **Routing: react-router.**
  - URLs `/queue`, `/history` and `/history/:taskId`; `/` redirects to `/queue`, and unknown paths show a not-found view.
  - Deep links and the back button work, and a reload keeps the view.
  - The backend's SPA fallback (item 02) serves `index.html` for these paths.
- **State: XState v5 with `@xstate/react`.**
  - Machines model what the client controls:
    - the SSE connection: connecting → live → reconnecting; every `snapshot` replaces the state;
    - each command's lifecycle: idle → sending → done or failed;
    - which commands a task's status allows.
  - The UI cannot send a command the machine does not allow.
  - Server state is applied as received. Task updates from `snapshot` and events are applied even when they do not follow the transition table: such an update is logged, not rejected, and the next snapshot repairs any gap.
- **One transition table.** The allowed transitions and commands come from `docs/api/task-transitions.json` (contract data, ADR 0029). The frontend machine reads it; a backend test checks that `TaskStatus` has exactly these transitions (item 11).

## Consequences
- Three new frontend dependencies (`react-router`, `xstate`, `@xstate/react`) and `msw` for tests and `pnpm dev:mock`. Each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash, which the user verifies with `nix build .` (ADR 0012).
- A status rule changes in one place: the contract file. The backend check fails if `TaskStatus` drifts from it.
- Machines are tested as pure logic (events in, states and context out) in addition to the RTL view tests.
