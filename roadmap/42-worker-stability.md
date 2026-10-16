# 42 — Worker stability: no per-task exception may leave the task stuck or kill the queue, and no command may race a task's start

Status: spec-approved
Depends on: [11](../docs/specs/processing-engine.md) (done)
Blocks: —

## Goal

Whatever a single task's run throws — during setup, a phase, or a repository call — the task ends in a defined state and the worker keeps taking the next queued task. And a cancel, pause or delete that arrives while the worker is starting a task acts on that task's run exactly as it would a moment later: the process is stopped or frozen, and the status the command stored is the one the task keeps.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Evidence (`ProcessingEngine.runTask()`): `setRunningIfQueued` is followed by the settings lookup (`settings.get()`, item 32) and the work-directory creation **outside** the `try` that catches processing failures. A throw in either leaves the task `RUNNING` (no `failIfRunning`), and the exception propagates through `runInJob`'s `job.join()` into `workerLoop()` — the whole worker coroutine ends, so the queue stops taking tasks until a restart (a SupervisorJob supervises children, it does not restart them).
- Cancellation must stay distinct from ordinary failure (the existing `catch (e: CancellationException) { throw e }` is the contract to preserve).
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion that the queue survives an unexpected task failure.
- Added 2026-10-16 (the user, from `docs/known-issues.md`'s HIGH backend row, found in the 2026-10-13 review): **commands race a task's start.** `runInJob` launches the task's job (`ProcessingEngine.kt:449`, `scope.launch { runTask(task) }`) and registers it in `running` only afterwards (`:454`), so the job is already running when a command looks it up through `runningJob(id)` (`:480`). A command in that window gets `null`:
  - `cancelTask` (`:349-350`) skips `cancelAndJoin()` — the process is not stopped — yet `cancelIfPossible` stores CANCELED, since it accepts a RUNNING task (`db/TaskRepository.kt:321`);
  - `pauseTask` (`:216-220`) stores PAUSED with `runningHere` false, so neither the process nor the pause gate is frozen;
  - `deleteTask` goes through `cancelTask` (`:264-267`), so the row is deleted while the run continues.
  The run then finishes and `setCompleted` (`:508`; `db/TaskRepository.kt:273-282`, no status condition) overwrites the stored CANCELED or PAUSED with COMPLETED. The window is short (from `launch` to the registration), but the outcome is a wrong stored state.
- Item 40 keeps `setCompleted` the durable terminal write (`docs/specs/atomic-task-transitions.md:29`): a pause landing while the last process exits loses to the completion. That rule stays; this amendment closes the window instead of making the completion conditional.

## Decided (consultation, 2026-10-13)

- **Guarded path**: every task-specific step — `setRunningIfQueued`, the settings lookup (item 32), setup, `runInJob`'s phases and finalization — is inside the guarded path; any non-cancellation throw there marks the task FAILED with the message (today's rule) and the worker continues.
- **Loop guard**: `workerLoop()` wraps `runInJob` so an exception the guarded path itself cannot absorb (a failure inside the failure path, e.g. a repository call while recording the failure) still logs with task context, leaves the task in a terminal state (FAILED if not already terminal), and continues to the next queued task — the loop never dies.
- **Cancellation stays distinct**: `CancellationException` is rethrown (item 11/36 contract); only shutdown ends the loop.
- **Start race in scope** (2026-10-16, the user): the start race is planned here, not as its own item — it touches the same `runInJob`/`runTask` path.
- **No e2e for the start race** (2026-10-16, the user; ADR 0046): the window lasts from `launch` to the registration, which a browser cannot reach on purpose, so a browser test would pass with or without the fix. Deterministic engine tests are the proof; the item's existing e2e (a task that fails mid-run does not stop the queue) covers its user-visible feature.

## Test list (proposed; behaviors only)

- [ ] A settings read that throws marks the task FAILED (not stuck RUNNING), and the next queued task still runs
- [ ] An unexpected exception in a phase fails the task and the worker takes the next queued task — the loop never dies
- [ ] An exception thrown while recording a failure (inside the failure path itself) still leaves the task in a terminal state and the worker continues
- [ ] Cancellation stays an interruption (task resumes on recovery), never a FAILED (no regression of item 11/36 semantics)
- [ ] (e2e, ADR 0046) Against the real backend, a task that fails mid-run does not stop subsequent tasks
- [ ] (must not break) The engine suites (`ProcessingEngineFailureSpec`, …) stay green
- [ ] (amendment) A cancel that arrives while the worker is starting the task stops that run's process and the task stays CANCELED — never overwritten with COMPLETED
- [ ] (amendment) A pause that arrives while the worker is starting the task freezes that run (process and pause gate) and the task stays PAUSED until it is resumed; resuming continues it to COMPLETED
- [ ] (amendment) A delete that arrives while the worker is starting the task stops that run's process before the row is gone, and the run writes nothing afterwards
- [ ] (amendment, must not break) A pause whose write lands while a task's last process is exiting still loses to the completion (item 40's rule: the task ends COMPLETED and the next task is not held)

## Acceptance criteria

- No single task can stick the queue: every unexpected failure lands in a defined task state and the worker advances.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 3 after code triage (setup outside the guarded try; `job.join()` propagates into `workerLoop()`).
- 2026-10-13: consultation — guarded path covers all task setup through finalization; loop guard recovers a failure of the failure path (terminal state + log + continue); `CancellationException` stays rethrown. Work-directory failure test item dropped (item 44 deletes per-task work directories); added the failure-path item. Test list approved; status `spec-approved`.
- 2026-10-16: the user added the start race (known-issues HIGH backend row) to this item. Amendment approved: Goal and evidence extended, four test-list items added (cancel, pause and delete during a task's start; item 40's completion-over-pause rule must not break), no e2e for the race (engine tests are the proof). The backlog row is removed; the 2026-10-13 test list is unchanged. Status: `spec-approved`.