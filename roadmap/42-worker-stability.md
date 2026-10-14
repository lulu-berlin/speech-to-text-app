# 42 — Worker stability: no per-task exception may leave the task stuck or kill the queue

Status: spec-approved
Depends on: [11](../docs/specs/processing-engine.md) (done)
Blocks: —

## Goal

Whatever a single task's run throws — during setup, a phase, or a repository call — the task ends in a defined state and the worker keeps taking the next queued task.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Evidence (`ProcessingEngine.runTask()`): `setRunningIfQueued` is followed by the settings lookup (`settings.get()`, item 32) and the work-directory creation **outside** the `try` that catches processing failures. A throw in either leaves the task `RUNNING` (no `failIfRunning`), and the exception propagates through `runInJob`'s `job.join()` into `workerLoop()` — the whole worker coroutine ends, so the queue stops taking tasks until a restart (a SupervisorJob supervises children, it does not restart them).
- Cancellation must stay distinct from ordinary failure (the existing `catch (e: CancellationException) { throw e }` is the contract to preserve).
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion that the queue survives an unexpected task failure.

## Decided (consultation, 2026-10-13)

- **Guarded path**: every task-specific step — `setRunningIfQueued`, the settings lookup (item 32), setup, `runInJob`'s phases and finalization — is inside the guarded path; any non-cancellation throw there marks the task FAILED with the message (today's rule) and the worker continues.
- **Loop guard**: `workerLoop()` wraps `runInJob` so an exception the guarded path itself cannot absorb (a failure inside the failure path, e.g. a repository call while recording the failure) still logs with task context, leaves the task in a terminal state (FAILED if not already terminal), and continues to the next queued task — the loop never dies.
- **Cancellation stays distinct**: `CancellationException` is rethrown (item 11/36 contract); only shutdown ends the loop.

## Test list (proposed; behaviors only)

- [ ] A settings read that throws marks the task FAILED (not stuck RUNNING), and the next queued task still runs
- [ ] An unexpected exception in a phase fails the task and the worker takes the next queued task — the loop never dies
- [ ] An exception thrown while recording a failure (inside the failure path itself) still leaves the task in a terminal state and the worker continues
- [ ] Cancellation stays an interruption (task resumes on recovery), never a FAILED (no regression of item 11/36 semantics)
- [ ] (e2e, ADR 0046) Against the real backend, a task that fails mid-run does not stop subsequent tasks
- [ ] (must not break) The engine suites (`ProcessingEngineFailureSpec`, …) stay green

## Acceptance criteria

- No single task can stick the queue: every unexpected failure lands in a defined task state and the worker advances.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 3 after code triage (setup outside the guarded try; `job.join()` propagates into `workerLoop()`).
- 2026-10-13: consultation — guarded path covers all task setup through finalization; loop guard recovers a failure of the failure path (terminal state + log + continue); `CancellationException` stays rethrown. Work-directory failure test item dropped (item 44 deletes per-task work directories); added the failure-path item. Test list approved; status `spec-approved`.