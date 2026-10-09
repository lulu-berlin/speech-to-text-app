# 0029 — Contract-first frontend with shared API examples

Status: accepted · Date: 2026-10-09

## Context
The frontend items (13–15, 17) depended on item 12 (HTTP API and SSE), which depends on the engine (11) and diarization (09). So no frontend work could start until the whole backend was done. The frontend tests already mock the API with MSW. What the frontend needs from item 12 is the API contract (routes, JSON shapes, SSE events, error codes), not a running server. If mocks are written separately from the backend, the two can drift apart unnoticed.

## Decision
- **Contract first.** Item 12's approved spec is the API contract. Frontend items depend on item 12 being `spec-approved`, not done ("spec-approved only" in `Depends on`), and are built and merged against MSW mocks.
- **One set of examples.** [`docs/api/`](../api/) holds one example JSON file per response body, SSE event payload and error. The MSW handlers serve these files and nothing else. Item 12's route tests check that every real response and event has the example's structure (keys, value types, nullability). A contract change edits the example and item 12's contract section in the same commit.
- **End-to-end check afterwards.** The checks of the frontend items that need the running backend move to a separate item (21), done after items 12, 14 and 15 and their merges. Drift found there is fixed in the side that deviates from `docs/api/`.

## Consequences
- The frontend runs in parallel with items 09–12; more workspaces run at once, and each needs the user's per-cycle checkpoints.
- Changing the contract after the frontend has used it means rework on both sides. The shared examples make such a change visible in tests on both sides, but do not prevent it.
- AGENTS.md workflow step 1 allows a "spec-approved only" dependency.
- No code generation between Kotlin and TypeScript: the contract is the spec text plus the example files.
