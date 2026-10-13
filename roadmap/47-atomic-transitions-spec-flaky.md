# 47 — ProcessingEngineAtomicTransitionsSpec is schedule-dependent (flaky)

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done)
Blocks: —

## Goal

Make `ProcessingEngineAtomicTransitionsSpec`'s "a running task whose accepted pause's write commits
before its completion's" test deterministic, so the item-21/40 command-race coverage never fails a
`gradle check` by scheduling luck.

## Evidence

- Reported 2026-10-13 by item 33's workspace rebase gate (working on plain `main`, not item 33's
  code): the test failed with `org.opentest4j.AssertionFailedError: expected:<[1L, 2L]> but was:<[1L]>`
  at `ProcessingEngineAtomicTransitionsSpec.kt:302`, reproduced on a detached `main` worktree.
- Main-tree agent stress runs (same day): failed 1 of 10 isolated runs; then 25 consecutive passes.
  So the flake is real but sparse.
- The failing assertion is the new item-40 regression pin's two-event receive:
  `val completedIds = listOf(fixture.completed.receive(), fixture.completed.receive()); completedIds.toSet() shouldBe setOf(first, next)`.
  The actual `{1}` means both receives returned task A's id — the worker published A's COMPLETED
  twice, or B's COMPLETED never arrived and something else re-sent A's. The race under test drives
  the writer lock with three writers (the worker's own chunk-status write, the pause's conditional
  write, the test's manual `setCompleted`), and the manual `setCompleted` does not publish; the
  assertion assumes exactly two COMPLETED events in a specific scheduling of the worker's flow.
- The test was added as a "green on arrival" regression pin for item 40's second review (the pause
  that wins its write and then loses to the completion must not hold the next run's gate). It pins a
  real invariant, but its event-count assertion is a second, schedule-dependent claim.

## Proposal

To be settled in the consultation. Starting point: keep the invariant assertions (both tasks end
COMPLETED, no dead started PAUSED row, `diarizer.passedGate` true) and drop or make robust the
two-event receive — e.g. `pumpUntil` the statuses instead of counting events, or assert the event
set contains both ids via a bounded drain after the worker finishes. Reproduce the failure first
(loop the spec until it trips), then fix, then run the spec in a loop (e.g. 50x) plus the full
suite.

## Test list (proposed; behaviors only)

- [ ] The spec passes 50 consecutive isolated runs (the flake's observed frequency is ≤ 1/10, so 50
      clean runs bound the residual probability)
- [ ] The invariant still holds when the test is re-run: A ends COMPLETED, no dead started PAUSED
      row, B diarizes and completes (the assertions that made the test a pin are unchanged)
- [ ] (must not break) `gradle check` and `gradle integrationTest` stay green

## Acceptance criteria

- The command-race spec never fails by scheduling luck: the added pin is deterministic.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed by the main-tree agent after the item-33 rebase gate hit it on plain `main` and
  stress runs reproduced the flake (1/10, then 25 clean). Kept out of item 33's merge per the user.