# 04 — Queue domain model

Status: in-progress
Depends on: [03](../docs/specs/database-schema.md) (done)

## Goal

Define the pure Kotlin rules for task status transitions, queue reordering and the queue paused flag, independent of I/O, so the engine (item 11) and API (item 12) share one source of truth.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): Kotlin backend.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pausing a running task suspends it in place rather than discarding progress.

## Proposal (approved 2026-10-06)

- Pure Kotlin state machine for task status (no DB, no coroutines). Allowed transitions:
  - `QUEUED` → `RUNNING`, `PAUSED`, `CANCELED`
  - `RUNNING` → `PAUSED`, `COMPLETED`, `FAILED`, `CANCELED`
  - `PAUSED` → `QUEUED` (resume: the task re-enters the queue; the engine starts it only when it is picked, so it may wait behind higher-priority tasks), `CANCELED`
  - Everything else is illegal and returns a typed error (no exception for expected rejections).
- At most one task is `RUNNING` at a time: `QUEUED → RUNNING` is accepted only while no other task is `RUNNING`. This is the only transition whose legality depends on the whole-queue state; every other transition is decided per task. The queue aggregate (task statuses + paused flag) enforces it (confirmed 2026-10-06).
- Terminal statuses: `COMPLETED`, `FAILED`, `CANCELED`.
- A `PAUSED` task keeps its queue position and can be reordered while paused; the engine (item 11) skips it when it picks the next task (consultation 2026-10-06).
- Terminal statuses are final: no transition re-queues a `COMPLETED`, `FAILED` or `CANCELED` task. A retry needs its own feature, because re-uploading the same audio is rejected by the `UNIQUE` `sha256` (ADR 0018) and a retry would have to reuse the existing task (consultation 2026-10-06).
- Deleting a history entry is out of scope here: item 12 exposes the existing `TaskRepository.delete`, which the schema cascade already backs (consultation 2026-10-06).
- Reorder = the full ordered list of ids of all non-terminal tasks; rejected if the id set differs from the current non-terminal set (missing, extra or duplicate ids).
- Queue paused flag modeled as part of the queue state (pausing an already paused queue is a no-op).

## Open questions (answered 2026-10-06)

- May a `PAUSED` task keep its queue position (and be reordered) while paused? → **Yes**: it keeps its slot and stays reorderable; the engine skips it.
- May `COMPLETED` / `FAILED` / `CANCELED` tasks be re-queued (retry)? → **No**: terminal is final; a retry is a future feature of its own.
- Is deleting history entries in scope (here or in item 12)? → **Item 12**; the domain model defines no deletion semantics.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-06 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] Each allowed transition is accepted (table-driven)
- [x] Every transition not in the allowed table is rejected, including any transition out of a terminal status (so a retry of a `COMPLETED`, `FAILED` or `CANCELED` task is rejected) (table-driven over all status pairs)
- [x] A `PAUSED` task keeps its queue position and can be reordered while paused
- [x] Reordering with exactly the current non-terminal ids applies the new order
- [x] Reordering with a missing id is rejected
- [x] Reordering with an unknown or terminal-task id is rejected
- [x] Reordering with a duplicated id is rejected
- [x] Pausing and resuming the queue toggles the paused flag
- [ ] Pausing an already paused queue and resuming an already running queue are no-ops
- [ ] A `QUEUED` task cannot transition to `RUNNING` while another task is `RUNNING`; once no task is `RUNNING`, the transition is accepted (discovered)
- [ ] Reordering a paused queue keeps it paused (discovered)

## Acceptance criteria

- The transition table in code matches the Proposal (as amended by the user's answers) and is covered for every status pair.
- The domain module has no dependency on the database, Ktor or coroutines.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-06: consultation decisions approved: a `PAUSED` task keeps its queue position and stays reorderable (the engine skips it); terminal statuses are final, with no re-queue/retry transition; deleting history entries belongs to item 12. Test list of 9 items approved with the spec. No new ADR: these are local queue semantics, recorded here and in the Proposal.
- 2026-10-06 (during cycle 1): `PAUSED` → `RUNNING` removed from the transition table. Resuming a paused task always returns it to the queue (`PAUSED` → `QUEUED`); the engine is the only component that moves a task to `RUNNING`, so a resumed task may wait behind higher-priority tasks instead of jumping straight to running. Recorded in the Proposal; no new ADR (local queue semantics).
- 2026-10-06 (during cycle 1): at most one task may be `RUNNING` at a time (concurrency = 1, matching item 11's single-worker proposal). `QUEUED → RUNNING` is rejected while any other task is `RUNNING` — the only transition gated by whole-queue state; the queue aggregate (task statuses + paused flag) enforces it. Added test item 10 `(discovered)`. Recorded in the Proposal; no new ADR (local queue semantics).
- 2026-10-06 (after cycle 8): added test item 11 `(discovered)`: reordering a paused queue keeps it paused (`reorder` dropped the flag). Done before item 9.
