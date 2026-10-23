# 81 — A row command clicked while another one is in flight is dropped

Status: draft
Depends on: —
Blocks: —

## Goal

A queue row never loses a click. Pressing Resume while the row's Pause request is still in flight
must not leave the task PAUSED with the queue stalled: either the command is accepted once the
previous one settles, or the row does not offer it until then. Today the click is discarded
silently, and a started PAUSED task blocks the whole queue until the user clicks Resume again.

## Evidence

- **The backend publishes PAUSED before it answers.** `ProcessingEngine.pauseTask` calls
  `publish(id)` (the SSE update that makes the row show Resume) and only then
  `freezeCurrentRun()`; the route answers with the task after `pauseTask` returns
  (`backend/src/main/kotlin/stt/engine/ProcessingEngine.kt:234-237`;
  `backend/src/main/kotlin/stt/http/TaskCommandRoutes.kt:40-42`). So the UI shows Resume while the
  `POST /api/tasks/{id}/pause` request is still open.
- **The row's buttons are enabled from that SSE status, not from the request.**
  `TaskRow.tsx:253-262` renders every command `isAllowedCommand(command, task.status)` allows and
  `send({ type: 'ACTIVATE', … })` on click, so Resume is clickable during the pause request.
- **The row action machine discards an `ACTIVATE` while a command runs.**
  `frontend/src/features/queue/rowActionMachine.ts:102-119`: the `running` state only invokes `run`
  and handles its `onDone`/`onError`; it has no `ACTIVATE` transition, so the event is consumed by
  no handler. A click there is a no-op — no error, no retry.
- **The window is the rest of the pause request.** Measured 2026-10-23 on this machine: the first
  `POST /pause` of a fresh backend 20-25 ms, later ones 12-14 ms. `main` therefore drops such a
  click only in that ~20 ms slot.
- **It stalls the queue, not just the row.** A started PAUSED task is the queue's only blocker
  (item 40's rule, stated in `e2e/tests/40-atomic-transitions.spec.ts:8-12`), so the tasks behind it
  never start until a user clicks Resume again.
- **It broke CI twice on item 49's branch, which is how it was found.** `1c1c312a` loaded
  libc inside the first signal, widening the window to ~106-141 ms; `40-atomic-transitions` then
  failed 4 of 6 local runs and `Linux (e2e)` failed in runs #223 (attempt 1) and #239, the first
  failure at `e2e/tests/40-atomic-transitions.spec.ts:46` ("Resume clicked as soon as it is
  visible"), after which a task kept the serial suite's queue so 13 later specs failed their
  "queue idle" waits. Reverting that lazy load (`a438153a`) restored the ~20 ms window and the
  spec passed 10 of 10, so the race is pre-existing, not item 49's.
- The e2e journey clicks Resume exactly in that window, so the flake can still surface on `main`
  at a lower rate; a lost click costs the whole `Linux (e2e)` job (about 30 minutes) and hides
  every later spec's result.

## Proposal

Two independent changes; the first closes the loss, the second shrinks the window.

1. **The row action machine keeps a command that arrives while one runs.** Handle `ACTIVATE` in
   `running` by remembering the event and sending it back to the machine in `onDone`/`onError`
   (XState's `forwardTo`/self-send), so the click runs after the in-flight command settles. Errors
   of the first command still surface as today; a confirm step (`confirming`) must not be skipped
   for the queued event.
2. **Never offer a command the row cannot take.** Publish the pause (and any status change a
   command makes) after the run is frozen, or keep the row's controls disabled until the
   `POST` answers and the returned task is applied — whichever is smaller. The frontend ships
   both readers of the status, so the UI-side fix alone is enough for the visible symptom; the
   backend order decides how long the page can be stale.

Rejected alternatives:

- **Only disabling the buttons while the request is in flight** changes nothing unless it is done
  from the request's own state, not the SSE status; the SSE status is what turns Resume on early.
- **Only reordering the backend publish** leaves the window while the response travels back; the
  machine must not discard clicks in any case.

## Open questions

- Does the console/Settings UI have the same "command while in flight" hole, or is it specific to
  the row machine? (The queue-wide Pause switch has its own machine, `queuePauseMachine.ts`.)
- Should a dropped-then-resent click surface as an error if the task changed state meanwhile
  (e.g. it completed), or simply be ignored, as a completion-won race is today?

## Test list (proposed; to be approved at the consultation)

- [ ] A Resume click during the row's Pause request is applied once that request settles, and the
      task runs again
- [ ] A command clicked during a request that then fails runs after the failure is surfaced
- [ ] The resume's confirmation rules still hold (a queued `cancel` still asks for confirmation)
- [ ] (must not break) The row takes a second command after a successful one, and a failed action
      can start the next one (item 40's behaviour)
- [ ] e2e: `40-atomic-transitions` stays green with Resume clicked as soon as it is visible, run
      repeatedly against the real backend
- [ ] (must not break) The queue-wide Pause switch and the Settings page behave as today

## Acceptance criteria

- No row click is discarded: a command clicked while another is in flight runs (or is visibly
  refused), and a started PAUSED task never blocks the queue without the user's click landing.
- `40-atomic-transitions` passes repeatedly against the real backend, and the `Linux (e2e)` job no
  longer fails on the lost-click signature.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, from the MED row in
  `docs/known-issues.md` recorded on `roadmap/49-windows-support` (`a438153a`). That row lives only
  on item 49's branch; the root agent removes it while doing item 49's PR prep, so the issue leaves
  the backlog with this item. Not yet discussed.
