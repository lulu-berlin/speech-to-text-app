# 04 — Queue domain model

Status: draft
Depends on: [03](03-database-schema.md)

## Goal

Define the pure Kotlin rules for task status transitions, queue reordering and the queue paused flag, independent of I/O, so the engine (item 11) and API (item 12) share one source of truth.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Kotlin backend.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pausing a running task suspends it in place rather than discarding progress.

## Proposal (pending approval)

- Pure Kotlin state machine for task status (no DB, no coroutines). Allowed transitions:
  - `QUEUED` → `RUNNING`, `PAUSED`, `CANCELED`
  - `RUNNING` → `PAUSED`, `COMPLETED`, `FAILED`, `CANCELED`
  - `PAUSED` → `QUEUED` (resume of a task that was waiting), `RUNNING` (resume of the running task), `CANCELED`
  - Everything else is illegal and returns a typed error (no exception for expected rejections).
- Terminal statuses: `COMPLETED`, `FAILED`, `CANCELED`.
- Reorder = the full ordered list of ids of all non-terminal tasks; rejected if the id set differs from the current non-terminal set (missing, extra or duplicate ids).
- Queue paused flag modeled as part of the queue state (pausing an already paused queue is a no-op).

## Open questions for the user

- May a `PAUSED` task keep its queue position (and be reordered) while paused?
- May `COMPLETED` / `FAILED` / `CANCELED` tasks be re-queued (retry)?
- Is deleting history entries in scope (here or in item 12)?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Each allowed transition is accepted (table-driven)
- [ ] Every transition not in the allowed table is rejected, including any transition out of a terminal status (table-driven over all status pairs)
- [ ] Reordering with exactly the current non-terminal ids applies the new order
- [ ] Reordering with a missing id is rejected
- [ ] Reordering with an unknown or terminal-task id is rejected
- [ ] Reordering with a duplicated id is rejected
- [ ] Pausing and resuming the queue toggles the paused flag

## Acceptance criteria

- The transition table in code matches the Proposal (as amended by the user's answers) and is covered for every status pair.
- The domain module has no dependency on the database, Ktor or coroutines.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
