# 21 — Frontend–backend end-to-end check

Status: draft
Depends on: [12](12-http-api-and-sse.md), [13](13-frontend-foundation.md), [14](14-frontend-queue-ui.md), [15](15-frontend-history-ui.md)

## Goal

Verify the frontend, built against item 12's contract with MSW mocks, against the real running backend, and fix any drift between the `docs/api/` contract examples, the backend and the frontend.

## Already decided

- Contract-first frontend (consultation 2026-10-09): items 13–15 are built and merged against item 12's approved contract using MSW mocks. Their checks against the running backend are moved here.
- Contract examples live in [`docs/api/`](../docs/api/). Item 12's route tests check the real responses against them, and the MSW handlers serve them.

## Proposal (pending approval)

- Run the built frontend served by the backend (`STT_STATIC_DIR`), with real ffmpeg, whisper-cli and the models, on short fixture clips.
- Walk through the checks moved from items 13–15 (test list below). A failure caused by contract drift is fixed in the side that deviates from `docs/api/`. If the contract itself has to change, the example, item 12's spec section and both sides change together.
- Decide whether part of this becomes an automated browser test (e.g. Playwright via devenv) or stays a user-run checklist.

## Open questions for the user

- Automated browser test (new dev dependency, ADR 0010) or a documented manual checklist run by the user?
- Should item 16 (Nix flake package) depend on this item, so the package is built only after the end-to-end check?

## Test list (written with the user; Canon TDD step 1)

Seed (the acceptance criteria moved from items 13–15), to be refined and approved with the user:

- [ ] The shell loads and switches between Queue and History; the browser shows a live `/api/events` stream that reconnects after a backend restart (from item 13)
- [ ] Uploading several files queues them in order; pause/resume/cancel, delete and reorder visibly take effect; both progress bars advance live without page reload (from item 14)
- [ ] A completed transcription appears in History, its turns are readable, every download produces the matching file, and the audio plays and seeks (from item 15)

## Acceptance criteria

- Every test-list item passes against the running backend.
- `docs/api/`, the backend and the frontend agree; any drift found is fixed and recorded.
- All test-list items ticked; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
