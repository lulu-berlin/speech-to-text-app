# 65 — `59-row-color`'s chunking-phase observation is flaky

Status: done (roadmap item 65, approved 2026-10-24)
Depends on: —
Blocks: —

## Goal

The e2e journey's row-color proof (item 59, Proposal 3) must observe the phase colors
deterministically. Today `e2e/tests/59-row-color.spec.ts:42-47` polls for a status line whose text
starts with `Chunking…`; the chunking phase of the color clip is sub-second, and once the row has
moved on the poll can only return null for the rest of its 120 s window even though the app behaved
correctly.

## Evidence

- 2026-10-19 01:07 UTC push run on `main` `d3ebbe19` (`39183853717`): `E2E (Playwright)` failed on
  `tests/59-row-color.spec.ts:8` — `expect.poll(…).not.toBeNull()` at `:47`, `Received: null`,
  `Timeout 120000ms exceeded`; 39/40 passed.
- The same commit's schedule runs `39204522533` (05:25) and `39216689878` (07:32) passed the e2e
  job; the item-62 worktree's local journey passed 40/40. Flaky, not a regression.
- The run's five `Task N failed … Invalid data found when processing input` lines are the suite's
  five deliberate undecodable fixtures (`broken.wav`, `broken-2.wav`, `broken-stability.wav`,
  `broken-59.wav`, `broken-59-status.wav`; `e2e/lib/backend.ts:59,65,80,87,93`); the specs that
  use them passed.
- Before the upload the spec verified the queue idle (`59-row-color.spec.ts:20-27`), and later
  specs saw running tasks, so the queue and engine were healthy.
- `COLOR_ROW_CLIP` is the sample looped 5× (~55 s; `e2e/lib/backend.ts:193`); its chunking phase is
  well under a second on the runner, while the poll samples every 100 ms.
- The run's trace (the row's state at timeout) needs an authenticated artifact download and was not
  examined; the mechanism above is `[INFERENCE]` from the spec, the queue logs and the reruns.

## Proposal

1. Sample the row inside the page from before the upload: install a collector that records every
   (phase prefix, computed color) pair the row ever shows — any `span` whose text starts with
   `Chunking…`, `Transcribing…` or `Diarizing…` — then stop it after the task completes. No phase
   can fall between two RPC round trips.
2. Assert every observed phase's color is pairwise different — `Chunking…`, `Transcribing…` and
   `Diarizing…` — matching item 59's Proposal 3. If the UI ever genuinely skips a phase, the
   assertion names the pairs that were seen instead of timing out on one.
3. Test the mechanism itself: inject a synthetic status line that shows one phase for a single
   frame and then another, run the same collector over it, and assert both pairs are recorded.

## Decided (consultation, 2026-10-19)

- **The collector lives in `59-row-color.spec.ts`** (the user): one consumer, so no shared helper
  until a second spec needs it.
- **The spec stops observing when the task's row leaves the Pending tasks list, then asserts the
  terminal badge in History**: the collector needs the queue view mounted, so
  `expectCompletedOrCanceled(page, COLOR_ROW_CLIP)` (`e2e/tests/helpers/ui.ts:144`) follows the
  stop; both fixed-timeout phase polls are gone.
- **All observed phases must read differently** (the user): chunking, transcribing and diarizing
  are asserted pairwise different, as item 59's Proposal 3 words it; the three theme tokens are
  distinct by construction (`TaskRow.tsx:36-40`).
- **e2e stance**: the item changes only the e2e suite and its deliverable is that e2e; no app
  feature changes, so no additional feature e2e is added (ADR 0046's build/packaging/refactor-only
  exemption does not literally cover test-only items, hence this explicit reason).

## Test list (approved 2026-10-19; behaviors only)

- [x] (basic) The row-color journey observes one running task's status line through its phases and
      asserts the recorded `Chunking…` and `Transcribing…` colors differ.
- [x] (basic) The same journey records `Diarizing…` and its color is distinct from both — all three
      phases read differently, as item 59's Proposal 3 claims.
- [x] (edge — the flake's regression) A phase that renders for a single frame is recorded: a
      synthetic status line shows `Chunking… · 0:01` for one frame and then
      `Transcribing… 42% · 0:02`, and the observation records both pairs.
- [x] (edge) The observation is scoped to the uploaded row: a sibling synthetic row's
      `Transcribing… 7%` line is not recorded against the target row.
- [x] (must not break) The spec still waits for an idle queue before its upload; the shared backend
      state and the other 39 journey specs stay green.
- [x] (must not break) The observation ends when the task's row leaves the Pending tasks list;
      `expectCompletedOrCanceled` is then asserted on History (the collector needs the queue view
      mounted), with no fixed 120 s/240 s phase window.
- [x] (discovered) The observation reads the Pending tasks row, not the Uploads row that carries the
      same filename while the upload settles (`144a078c`).
- [x] (discovered) Each observed phase shows exactly one color: a phase that renders in two colors
      fails with the observed pairs named.

## Acceptance criteria

- The row-color spec observes every phase color without keying on a single transient phase; a phase
  that renders for one frame is still captured; repeated CI runs of the e2e job pass.
- The rest of the journey is unchanged; all test-list items ticked and green; user approved the
  diff.

## Approval log

- 2026-10-19: filed as a draft by the root agent from the failed push run `39183853717` (the
  known-issues entry moved here). Not yet discussed.
- 2026-10-19 (consultation, confirmed by the user): the collector is spec-local; the spec stops on
  the task's own finish signal via `expectCompletedOrCanceled`; all observed phases (chunking,
  transcribing, diarizing) must read differently; the e2e stance is stated above. Test list
  approved as worded. Status: spec-approved.
- 2026-10-24 (review, confirmed by the user): item 6 and the matching Decided bullet now say the
  observation ends when the row leaves the Pending tasks list and `expectCompletedOrCanceled`
  follows on History (the collector needs the queue view mounted); two discovered scenarios were
  appended to the test list — the pending-task row versus the upload row, and exactly one color per
  phase.
- 2026-10-24: diff reviewed by the root agent and approved by the user; the e2e verification runs
  in the pull request's CI. Status: done (roadmap item 65).
