# 0057 — A RUNNING task whose process is gone goes back to QUEUED

Status: accepted · Date: 2026-10-17

## Context

`ProcessRunner` surfaces a process killed by SIGTERM (exit 143) as a `CancellationException`, so that a
shutdown never records a task as FAILED (roadmap item 34). Until now the engine treated every such
cancellation as the app quitting: the task stayed RUNNING, and the next start's restart recovery (ADR 0004)
queued it again. But a SIGTERM can also reach only the task's process while the app keeps running — a
manual `kill`, or a memory-pressure killer such as earlyoom, which sends SIGTERM before SIGKILL. Then no
process works on the task, yet it was stored as RUNNING; item 42 found that the transcription phase even
hung the worker in that case.

Item 04's transition table had no `RUNNING → QUEUED`: restart recovery wrote it in the repository,
outside the table. A live engine writing it would break the shared contract
(`docs/api/task-transitions.json`): the frontend warns on every task update the table does not allow.

## Decision

- **A task is RUNNING only while a process works on it.** When its process is killed by SIGTERM while
  the app keeps running (the cancellation arrives while the task's own coroutine is still active), the
  engine puts the task back to QUEUED at once — phase cleared, queue position kept, only if it is still
  RUNNING — and the worker runs it again from its interrupted chunk, as after a restart.
- **Quitting the app needs no extra write.** The task's coroutine is then cancelled itself, the task is
  left RUNNING, and the next start's recovery queues it again. A crash cannot write anything either, so
  the startup recovery is the one place that covers both.
- **`RUNNING → QUEUED` is an allowed transition** in item 04's table and in
  `docs/api/task-transitions.json`. No command uses it (the `commands` section is unchanged), so no new
  control is offered in the UI.
- **No `INTERRUPTED` status.** It would last exactly one write before QUEUED, and cost a migration of the
  status column's CHECK, API types, UI labels, a row in the table and a second event.

## Consequences

- A process killed from outside never fails a task and never leaves it RUNNING without a process: the
  task reruns from its last DONE chunk.
- Something that keeps killing the process makes the task rerun each time; accepted (the user, item 42).
- The resume and retry commands check the table before their conditional write; for a RUNNING task they
  now pass that check and lose at the write, which still answers `Illegal(RUNNING, QUEUED)` (HTTP 409) as
  before.
- Restart recovery's `RUNNING → QUEUED` is now a transition of the table rather than an exception to it.
