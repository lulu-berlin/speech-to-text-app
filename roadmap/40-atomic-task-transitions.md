# 40 — Task transitions are not atomic (pause/resume race a finishing task)

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done)
Blocks: —

## Goal

A task command never overwrites a newer state: when a completion, a cancel and a pause race, exactly one wins, the row stays consistent, and the queue always keeps working.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Evidence (`ProcessingEngine.command()` + `TaskRepository`): `command()` reads the status, checks the transition table (`canTransitionTo`), then runs the body — and `pauseTask`/`resumeTask` call the **unconditional** `TaskRepository.setStatus` after that check. Between the read and the write (two transactions, the worker coroutine interleaves at I/O suspension points) a finish can land: the pause then writes `PAUSED` over `COMPLETED`. The queue then blocks: `nextTask()` waits while any `startedPaused()` task exists, and `PAUSED` has no way forward on its own — a completed task is un-recoverable until a user action. Resume and cancel have the same class of interleaving.
- The repo already has the right primitive: `setRunningIfQueued`, `failIfRunning` and `updateTaskIfRow` are conditional updates, and cancel/fail/retry use them. Pause/resume (and the generic `command` gate) are the gap.
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion the race never surfaces (the user's suite exercises pause and completion constantly).

## Proposal

To be settled in the consultation. Starting point: every transition is a conditional UPDATE inside the database transaction — `pauseIfRunning` (and `pauseIfQueued`), `resumeIfPaused`, with zero-rows → the transition lost the race → report it consistently. Where two operations "both" apply (delete-vs-complete), decide the winner once and keep it. Add deterministic concurrency tests (the reviewer's own recommendation) for pause-vs-complete, resume-vs-cancel and delete-vs-complete before touching anything else in the queue.

## Open questions (to be answered in the consultation)

- Who wins each race: completion over pause; cancel over resume; delete over everything (the delete path already cancels first — the outcome to pin)?

## Test list (proposed; behaviors only)

- [ ] A pause racing a completion leaves the task COMPLETED and the queue keeps working (deterministic concurrency test)
- [ ] A resume racing a cancel ends in exactly one of the two states, consistently, and the queue stays live
- [ ] Delete racing a completion: the row still goes and the file is freed (the current cancel-guard behavior pinned)
- [ ] Pausing a task whose row is no longer in the paused-able state is rejected, never a dead `PAUSED`
- [ ] (e2e, ADR 0046) Against the real backend, rapid pause/resume/cancel while a task nears completion never leaves the queue blocked or a task stuck
- [ ] (must not break) The engine command suites (`ProcessingEngineCancelSpec`, `ProcessingEngineTaskPauseSpec`, …) stay green

## Acceptance criteria

- The queue survives every command race: no stuck task, no blocked worker, one consistent outcome, exercised by the deterministic tests and the e2e journey.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 1 after code triage (unconditional `setStatus` behind a non-atomic `command()` gate).