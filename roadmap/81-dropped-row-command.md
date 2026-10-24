# 81 — A row command clicked while another one is in flight is dropped

Status: spec-approved
Depends on: —
Blocks: —

## Goal

A queue row never loses a click. Pressing Resume while the row's Pause request is still in flight
must not leave the task PAUSED with the queue stalled: the row does not offer its commands until the
request settles, and a command that still arrives meanwhile runs once it does. Today the click is
discarded silently, and a started PAUSED task blocks the whole queue until the user clicks Resume
again.

## Evidence

Line numbers are `main` at `0715685b`.

- **The backend publishes PAUSED before it freezes the run and answers.** `ProcessingEngine.pauseTask`
  calls `publish(id)` (the SSE update that makes the row show Resume) and only then
  `freezeCurrentRun()` (`backend/src/main/kotlin/stt/engine/ProcessingEngine.kt:236-238`); the route
  answers with the task after `pauseTask` returns (`backend/src/main/kotlin/stt/http/TaskCommandRoutes.kt:42`).
  So the UI shows Resume while the `POST /api/tasks/{id}/pause` request is still open, and an SSE
  reader can see a PAUSED task whose process still runs.
- **The row's buttons are enabled from that SSE status, not from the request.**
  `frontend/src/features/queue/TaskRow.tsx:249-262` renders every command
  `isAllowedCommand(command, task.status)` allows and sends `ACTIVATE` on click, so Resume is
  clickable during the pause request.
- **The row action machine discards an `ACTIVATE` while a command runs.**
  `frontend/src/features/queue/rowActionMachine.ts:102-119`: the `running` state only invokes `run`
  and handles its `onDone`/`onError`; it has no `ACTIVATE` transition, so the event is consumed by no
  handler. A click there is a no-op — no error, no retry.
- **The window is the rest of the pause request.** Measured 2026-10-23: the first `POST /pause` of a
  fresh backend 20-25 ms, later ones 12-14 ms. `main` therefore drops such a click only in that
  ~20 ms slot.
- **It stalls the queue, not just the row.** A started PAUSED task is the queue's only blocker
  (item 40's rule, stated in `e2e/tests/40-atomic-transitions.spec.ts:8-16`), so the tasks behind it
  never start until a user clicks Resume again.
- **It broke CI twice on item 49's branch, which is how it was found.** `1c1c312a` loaded libc
  inside the first signal, widening the window to ~106-141 ms; `40-atomic-transitions` then failed 4
  of 6 local runs and `Linux (e2e)` failed in runs #223 (attempt 1) and #239 at the spec's "Resume
  clicked as soon as it is visible" step, after which a task kept the serial suite's queue so 13
  later specs failed their "queue idle" waits. Reverting that lazy load (`a438153a`) restored the
  ~20 ms window and the spec passed 10 of 10, so the race is pre-existing, not item 49's.
- The e2e journey clicks Resume exactly in that window, so the flake can still surface on `main` at
  a lower rate; a lost click costs the whole `Linux (e2e)` job and hides every later spec's result.
- **The other command surfaces on `main`** (open question 1, checked 2026-10-24): the queue-wide
  Pause switch is disabled while it sends (`frontend/src/features/queue/QueueView.tsx:398`), and the
  Settings banner's dismiss is disabled while dismissing (`SettingsView.tsx:209`). History's Retry
  (`commandMachine`, `HistoryRow.tsx:83,124`) drops a second Retry during its request — the same
  command, so nothing is lost, but the button stays enabled.

## Proposal

1. **The row does not offer a command while one is in flight.** The row's command buttons are
   disabled from the row action machine's own `running` state (not from the SSE status) and enabled
   again when the request settles, successfully or not.
2. **A command that still arrives is kept, not dropped.** `running` handles `ACTIVATE` by
   remembering the event — the latest one replaces an earlier one — and replays it when the
   in-flight command settles (after its `onDone` or `onError`). On replay the command is checked
   against the transition table for the task's live status: one that no longer applies is dropped
   silently; one that needs confirmation (cancel) still goes through `confirming`.
3. **The backend publishes after the freeze.** `pauseTask` publishes the PAUSED task once
   `freezeCurrentRun()` has run, so an SSE reader never sees a PAUSED task whose process still runs.
4. **History's Retry is disabled while its request runs**, and enabled again if it fails.

Rejected alternatives:

- **Only disabling the buttons from the SSE status**: the SSE status is what turns Resume on early;
  the disabled state must come from the request.
- **Only reordering the backend publish**: leaves the window while the response travels back; the
  machine must not discard clicks in any case.
- **Surfacing a no-longer-allowed replay as an error (the server's 409)**: reports an error for a
  race the user did not cause.

## Decided (consultation, 2026-10-24)

- **Both**: the row disables its commands while one runs, and keeps (replays) any command that
  still arrives; of several, only the last one runs.
- **Backend order**: `pauseTask` publishes after `freezeCurrentRun()`.
- **Stale replay**: a kept command the transition table no longer allows for the live status is
  ignored silently, as a completion-won race is today (item 40).
- **Scope**: queue rows plus History's Retry; the queue-wide Pause switch and Settings already
  disable while they send.
- **e2e** (ADR 0046, this is an app behavior change): a new spec holds the Pause response in the
  browser with `page.route`, so the race is deterministic instead of a ~20 ms window;
  `40-atomic-transitions` must keep passing.

## Open questions

None. (Both settled 2026-10-24, see "Decided (consultation, 2026-10-24)" and the last Evidence
bullet.)

## Test list (approved 2026-10-24; behaviors only)

Queue row (Vitest):

- [ ] While a row command's request is in flight, the row's command buttons are disabled; they are
      enabled again once the request settles, successfully or not
- [ ] A command that still reaches the row while one is in flight (e.g. dispatched before the
      buttons re-render) runs once the first one settles
- [ ] Of several commands arriving while one is in flight, only the last one runs
- [ ] A kept command the transition table no longer allows for the task's live status is dropped:
      no request, no error
- [ ] A kept cancel still asks for confirmation before it runs
- [ ] If the in-flight command fails, its inline error shows, and the kept command then runs
      (clearing the error, as a new action does today)
- [ ] (must not break) The row takes a second command after a successful one, a failed action can
      start the next one (item 40), and the delete flow is unchanged

History (Vitest):

- [ ] History's Retry is disabled while its request runs, and enabled again if it fails

Backend (Kotest):

- [ ] Pausing a running task publishes PAUSED only after the run is frozen: a subscriber that
      receives the PAUSED update finds the process already paused
- [ ] (must not break) Pausing a queued (not started) task still publishes PAUSED and freezes
      nothing; the atomic-transition specs stay green

e2e (Playwright, real backend):

- [ ] With the Pause response held in the browser, the row's Resume is disabled until the response
      arrives; clicking it then resumes the task, which completes, and the queue behind it starts
- [ ] (must not break) `40-atomic-transitions` passes repeatedly with Resume clicked as soon as it
      is visible
- [ ] (must not break) The queue-wide Pause switch and the Settings page behave as today

## Acceptance criteria

- No row click is discarded: a command clicked while another is in flight is either refused
  visibly (the button is disabled) or runs once the first settles, and a started PAUSED task never
  blocks the queue without the user's click landing.
- `40-atomic-transitions` passes repeatedly against the real backend, and the `Linux (e2e)` job no
  longer fails on the lost-click signature.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, from the MED row in
  `docs/known-issues.md` recorded on `roadmap/49-windows-support` (`a438153a`). That row lives only
  on item 49's branch; the root agent removes it while doing item 49's PR prep, so the issue leaves
  the backlog with this item. Not yet discussed.
- 2026-10-24 (consultation, the user): both a disabled row while a command runs and a kept, replayed
  command (only the last one, dropped silently if no longer allowed, cancel still confirmed); the
  backend publishes PAUSED after the freeze; History's Retry is disabled while it runs; a
  deterministic e2e holds the Pause response with `page.route`. Open questions answered from `main`.
  Test list approved as worded, including the "only the last one runs" behavior. Status:
  spec-approved.
