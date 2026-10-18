# 0058 — A QUEUED task whose start write fails is FAILED

Status: accepted · Date: 2026-10-18

## Context

The worker marks a picked task RUNNING with a conditional `QUEUED → RUNNING` write before its run begins. When that
write itself throws (a database that cannot be written at the moment), the task is still QUEUED. Item 42's guard
records a failure with `failIfRunning`, which only touched RUNNING — so nothing was written, the worker took the
same QUEUED task again at once, and looped on it, filling the log with the same write error and never reaching the
next queued task.

Item 04's transition table had no `QUEUED → FAILED`: only RUNNING could fail, because a task normally leaves QUEUED
by becoming RUNNING first.

Two other routes were considered and rejected (item 42's consultation): writing the RUNNING status again and then
failing it (two more writes to a database that just refused one, and the retry cannot be pinned by a test — SQLite's
`RAISE(ABORT)` rolls back what a trigger records, so a trigger cannot tell a first attempt from a retry), and
writing CANCELED (allowed, but it claims the user cancelled the task).

## Decision

- **`QUEUED → FAILED` is an allowed transition** in item 04's table and in `docs/api/task-transitions.json`, for the
  one case where a task's start itself fails: the guard records the write's error, the task is FAILED with it and a
  `finished_at`, and the worker moves on to the next queued task.
- The repository's failure write (`failIfPending`) accepts QUEUED and RUNNING and still refuses every other status,
  so a failure that races a cancel, a pause or a completion keeps that newer status.
- No command uses the transition: `retry` stays the way out of FAILED.

## Consequences

- A start that cannot be persisted ends the task as FAILED instead of spinning the worker on it.
- The frontend needs the extra target in the shared table: it warns on every task update the table does not allow, and
  the FAILED event for a QUEUED task would have triggered that warning.
- A QUEUED task is now failed by a database refusal rather than only by a failure of its work; the stored error names
  the write that failed.
- Residual: if that FAILED write is refused as well, the task stays QUEUED and the worker could retry it at once.
  Recording the failure of the failure is the loop guard's problem (item 42, cycle 3); the case is not covered by a
  test today.
