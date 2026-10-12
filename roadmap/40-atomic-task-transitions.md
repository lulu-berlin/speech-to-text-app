# 40 — Task transitions are not atomic (pause/resume race a finishing task)

Status: in-progress
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

## Open questions (answered 2026-10-12)

The user asked for the concrete interleavings before deciding the winners. The clarified scenarios (recorded):

- **A — Pause vs completion** (RUNNING, worker finishing): today a late pause overwrites `COMPLETED` into `PAUSED`, and `startedPaused()` blocks the whole queue. Fix: pause writes only `if status IN (QUEUED, RUNNING)`; zero rows → 409. Winner: **completion**; the losing pause answers **409 illegal-transition** (user, 2026-10-12, the recommended option).
- **B — Resume vs cancel** (PAUSED): with resume also conditional (`if status = PAUSED`), both interleavings end `CANCELED` (cancel first → resume 409; resume first → cancel's conditional still allows it). Winner: **cancel**, by construction (user, 2026-10-12).
- **C — Delete vs completion**: today delete cancels first then removes the row regardless; a completion after the deletion hits no row and publishes nothing. Winner: **delete** (user, 2026-10-12) — already true; the item pins it with a test.

The mechanism is conditional updates on the command writes (pause, resume) plus `cancelIfPossible` as-is; `setCompleted` stays the durable terminal write. Today `cancelTask`/`deleteTask` are the safe ones; `pauseTask`/`resumeTask` are the two unconditional writers this item fixes.

## Test list (proposed; behaviors only)

- [x] A pause racing a completion leaves the task COMPLETED and the queue keeps working (deterministic concurrency test)
- [x] A resume racing a cancel ends in exactly one of the two states, consistently, and the queue stays live
- [x] Delete racing a completion: the row still goes and the file is freed (the current cancel-guard behavior pinned)
- [x] Pausing a task whose row is no longer in the paused-able state is rejected, never a dead `PAUSED`
- [x] (e2e, ADR 0046) Against the real backend, rapid pause/resume/cancel while a task nears completion never leaves the queue blocked or a task stuck
- [x] (must not break) The engine command suites (`ProcessingEngineCancelSpec`, `ProcessingEngineTaskPauseSpec`, …) stay green

Discovered 2026-10-12 in the e2e runs (approved by the user):

- [x] (discovered) The queue row's action machine accepts a second command on the same row (pause then resume from one row): the e2e journey exposed that `rowActionMachine` stays in `done` after one successful command and silently swallows the next `ACTIVATE` — the browser never sent the resume request (playwright trace proof) while the backend itself handles pause→resume correctly (curl repro: COMPLETED). Fix with a `QueueView` test (both endpoints called) then the machine change; then the e2e journey can pass.
- [x] (discovered) The settings e2e journey (`17-settings.spec.ts`) hands the shared backend back after its restart, like `13-restart.spec.ts`: it stopped its restarted backend in `finally` without starting a fresh one, so every later spec (now `40-atomic-transitions`) met a dead port (`ERR_CONNECTION_REFUSED`) in the full suite.

Added 2026-10-12 by the review (approved by the user):

- [ ] (review) A pause that is accepted but whose task still finishes (the pause landed after the task's last frozen process) leaves the next task free: the next task, including its DIARIZING phase, runs to completion — a stale pause never stalls the queue. No e2e of its own: the window is too narrow to hit reliably from a browser; the deterministic engine test covers it.
- [x] (review) Pausing a QUEUED task while another task runs leaves the running task untouched: its process is not frozen, its diarization is not held, and it completes; the paused task stays PAUSED and is not started
- [ ] (review, e2e, ADR 0046) Against the real backend, pausing a queued upload while another transcribes: the running one still completes, the paused one stays paused until resumed, then completes
- [ ] (discovered) A task paused between two of its processes (e.g. between two chunks, after the freeze missed the exiting process) starts no further process: it stops before its next chunk and stays PAUSED until resumed, then completes. (Found while checking the review's finding 1: `ProcessRunner.pause()` is a no-op with no run in flight and each run's end clears the freeze, and the chunk loop never checks for a pause — a probe saw chunk 1 start, and the task complete, while it was PAUSED.)
- [ ] (discovered) Resuming a PAUSED task that never started, while another task is paused in place, leaves that other task frozen, gated and PAUSED. (Found at the review-item-2 checkpoint: `resumeTask` calls `processControl.resume()` and `pauseGate.resume()` whichever task it resumes.)

## Acceptance criteria

- The queue survives every command race: no stuck task, no blocked worker, one consistent outcome, exercised by the deterministic tests and the e2e journey.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 1 after code triage (unconditional `setStatus` behind a non-atomic `command()` gate).
- 2026-10-12: the user confirmed the test list; requested the race-scenario clarification (recorded above) before deciding the winners. Pending: the winner decision.
- 2026-10-12: the user confirmed the winner set: completion over pause (losing pause → 409 illegal-transition), cancel over resume, delete over everything. Test list approved; status `spec-approved`.
- 2026-10-12: the user approved the refactor moving the row-action `done` state under `ready` (one ACTIVATE definition), and fixing the settings journey's backend handover inside this item (discovered in the full e2e suite).
- 2026-10-12: review: changes requested — a won pause can leave the pause gate closed and stall the next task; pausing a QUEUED task freezes the running one. Both approved into this item by the user (test-list items below).
- 2026-10-12: the user added the discovered pause-between-processes item to this item and chose review item 2 as the next cycle; fix design chosen by the user: `pauseTask` freezes the process and closes the gate only when the paused task is the one the worker runs (`runningJob(id) != null`, as `resumeTask` does). Green (`gradle check`: 269 tests, 0 failures).
- 2026-10-12: checkpoint after review item 2: no refactoring suggested (the `runningHere` guard is one line, used differently by pause and resume). The user added the discovered resume-mirror item; a second candidate (a pause racing the worker's pick of the same QUEUED task) was not added. Next cycle: review item 1.
