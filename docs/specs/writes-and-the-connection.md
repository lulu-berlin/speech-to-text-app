# 87 — Writes wait for the connection, and a control with nothing to do says so

Status: done (roadmap item 87, approved 2026-10-31)
Depends on: —
Blocks: — (a 1.0 gate, decided 2026-10-31)

## Goal

A control that writes is offered only while the app can carry the write out, and a write that fails
tells the user what happened in the app's words, never the browser's. A control with nothing to do
(a name already at its default) is not offered either.

## Evidence

- **User report 2026-10-31** (two observations in the transcript view):
  - "Reset name" is offered when the speaker's name is already the default, so it has nothing to
    reset.
  - With the backend disconnected, renaming a speaker shows
    `NetworkError when attempting to fetch resource.` under the field.
- **"Reset name" is guarded only against a send in flight**: `disabled={state.matches('sending')}`
  (`frontend/src/features/history/SpeakersPanel.tsx:190-198`), and the machine's `RESET` is
  unguarded (`speakerRenameMachine.ts:65`), so pressing it always sends `PUT {speaker: null}` even
  when the stored name is already what the server would write (`SpeakerRoutes.kt:61`,
  `defaultSpeakerName(speakerId)` = `"Speaker N+1"`, `Speaker.kt:10`).
- **The raw text is the fetch failure itself**: a transport failure rejects with the browser's
  `TypeError`, which `speakerRenameMachine`'s `onError` stores as if it were an `ApiError` and the
  panel prints via `error.message` (`SpeakersPanel.tsx:200`). Only the upload path maps a transport
  failure to its own message (`client.ts:176`, `'Network error'`); every `fetch`-based call
  (`client.ts:38-209`) lets the `TypeError` through.
- **The connection state exists but no write consults it**: `ConnectionContext` wraps
  `connectionMachine` (`frontend/src/app/ConnectionContext.tsx:6`), whose states are `connecting`,
  `reconnecting` and `live` (`connectionMachine.ts:65-82`); the header already shows "Reconnecting"
  (`AppHeader.tsx:146-150`), and nothing else disables or explains.
- **The write surface**: settings (`saveSettings`, `resetSettings`, `dismissDeviceFallback`,
  `client.ts:63-77`), the transcript and speakers panel (`renameSpeaker`, `editSegment`,
  `revertSegment`, `mergeSpeakers`, `resetMerges`, `client.ts:88-133`, plus item 73's reassign),
  the queue and history (`uploadTask`, `runTaskCommand`, `deleteTask`, `reorderQueue`,
  `setQueuePaused`, `client.ts:149-209`).

## Decided (consultation, 2026-10-31)

- **A new roadmap item** (the user): 87, not a backlog row and not folded into item 73.
- **A 1.0 gate** (the user): item 78 lists it.
- **Every control that writes is disabled while the connection is not live** (the user), reading,
  scrolling, playback of loaded audio and switching pages keep working.
- **A friendly message for a failed write** (the user): "Not saved: can't reach the app's service.
  Reconnecting…" instead of the browser's text, and the word "service", never "backend", in what
  the user reads.
- **A write already in flight when the connection drops** (the user): show the message and leave the
  retry to the user; no hidden retry.
- **The disabled look** (the user): disabled, with a `title`/description on each control; no extra
  banner.
- **Uploads** (the user): the submit is disabled while the connection is not live; files already
  chosen stay chosen.
- **An e2e journey for it** (the user): in scope.

## Proposal

1. **One notion of "writes enabled"**: a selector on `ConnectionContext` that is true only in
   `live`, used by every write control (not a per-panel copy), so a new control cannot forget it.
2. **Apply it to every control in the Evidence list**, and explain the disabled state where the
   control is (a `title` and an accessible description; the header's "Reconnecting" stays the
   banner).
3. **One transport error**: a `fetch` rejection becomes an `ApiError` with the app's message at the
   client boundary (`client.ts`), so every panel that prints an error prints that, and the raw
   `TypeError` never reaches a view.
4. **"Reset name" disabled when the stored name is the default** (`"Speaker " + (speakerId + 1)`),
   and enabled again after a rename; the machine's unguarded `RESET` goes away or is guarded.
5. **Amend `docs/specs/frontend-state-and-errors.md`** (the spec that owns visible errors and the
   settings owner) with the two rules.

## Open questions

None. (All settled 2026-10-31, see "Decided (consultation, 2026-10-31)".)

## Test list (approved 2026-10-31; behaviors only)

- [x] (ui) "Reset name" is disabled while the stored name is the default (`Speaker N+1`) and enabled
      after a rename; a reset leaves it disabled again, and no request is sent while it is disabled
- [x] (ui) With the connection not live, every control that writes is disabled — settings save, reset
      and dismiss; rename, reset name, reassign, merge and reset merges; segment edit and revert;
      upload submit; queue pause, reorder, cancel and retry; task delete — each naming why in its
      `title`/description
- [x] (ui) Reading keeps working while the connection is not live: the queue, a transcript, its
      turns and the settings page are shown, scrolling works, loaded audio plays, pages switch
- [x] (ui) When the connection is live again the same controls are enabled, without a reload
- [x] (app) A write whose request fails at the transport shows "Not saved: can't reach the app's
      service. Reconnecting…", never the browser's `NetworkError…` text, on every control that shows
      a write error
- [x] (must not break) A rejected write (`400`, `404`, `409` with a server message) still shows the
      server's message, and the existing frontend, backend and journey suites stay green
- [x] (e2e, ADR 0046) A journey stops the backend, sees the write controls disabled and the
      transcript still readable, restarts it, and sees a rename go through

## Amendment of 2026-10-31 (approved 2026-10-31)

Decided with the user, after the root review:

- **A resend starts over**: an upload the connection drop cut off is sent again from the beginning
  of the file when the live stream returns, not resumed.
- **A duplicate resend shows the usual notice**: a resend the service answers `409 duplicate-audio`
  (the first attempt had in fact landed) shows the existing duplicate notice naming that task,
  never an error row.
- **The upload controls stay disabled**: the drop zone and the file picker are offered only while
  the connection is live; the waiting row is the batch's own work, not a new control.

Test list:

- [x] (app) An upload the connection drop cut off waits and is sent again from the start when the live
      stream returns, without a reload and without an error row — including when the row's fetch failed
      outright (backend gone) and when the request hung with no answer
- [x] (app) While the row waits, every row behind it in the batch waits too; no row's request starts
      while the live stream is down
- [x] (app) A resend the service answers 409 duplicate-audio (the first attempt had in fact landed)
      shows the usual duplicate notice naming that task, never an error row
- [x] (app) An upload answered by the service without a duplicate (a real refusal, 400/413 or a 409 of
      another kind) keeps its server message and is never re-sent
- [x] (app) Leaving or reloading the page drops the waiting rows (the File handles are gone); nothing
      is stored for them
- [x] (e2e, ADR 0046) The item's journey gains: upload a clip, stop the backend mid-upload, see the row
      waiting (not an error), restart, and see that task finish uploading without a reload
      (the mid-upload journey is skipped on Windows: freezing the backend needs POSIX signals)
- [x] (spec) docs/specs/frontend-state-and-errors.md rule 2 records the one exception: an upload cut
      off by the connection waits and is re-sent in full; no other write is retried

## Review finding of 2026-10-31 (root review)

- [x] (app, review) A read whose request fails at the transport says "Can't reach the app's service.
      Reconnecting…" (no "Not saved"); write errors keep "Not saved: …"; the doubled prefixes
      "Retry failed: …" and "Delete failed: …" are gone; rule 2 of the spec is updated

## Review of 2026-10-31 (second root review)

- [x] (app) A transport error while the live stream is up ends the waiting row as an error naming the
      service after a 2–3 s grace, and the batch behind it continues; a real drop still waits and is
      re-sent, and the killed-backend journey stays green
- [x] (ui) A waiting upload row says "Waiting for the connection…", never the internal "queued", and
      the journeys' assertions use the app's wording
- [x] (e2e) The mid-upload journey's freeze sits inside try/finally: a failed upload or row assertion
      SIGKILLs the frozen group in the finally before the backend is handed over, so no later spec
      hangs
- [x] (spec) The Windows skip is recorded in the item's test-list item and in rule 2, since the
      Windows e2e gates merges

## CI failure of 2026-10-31 (PR head `fcfbe29a`)

- [x] (app, discovered) The abort listener is removed once an upload settles, so a finished request is
      never aborted by a later teardown, and a pending one still is
- [x] (app, discovered) Aborting an in-flight upload puts its row back in the queue with the batch
      waiting, and no unhandled error escapes; the journey's teardown and the QueueView fakes exercise
      it
- [x] (e2e, discovered) The journey's resend outcome is either a duplicate notice or a finished
      upload, and neither ever shows a transport error

## Acceptance criteria

- Every control that writes is disabled while the connection is not live and enabled again when it
  is, on every page; reading keeps working.
- A failed write shows the app's message, on every control that can show one; no view prints a
  browser transport error.
- "Reset name" is disabled while the stored name is the default and sends nothing.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-31: filed as a draft by the root agent from two user observations on the transcript view
  (a live "Reset name" and a raw `NetworkError` while disconnected), after locating both in the
  code. Scope, the 1.0 gate and the friendly message decided with the user; the open questions and
  the test list are next.
- 2026-10-31: consultation with the user: message "Not saved: can't reach the app's service.
  Reconnecting…" ("service", never "backend", in what the user reads); a failed write is not
  retried; disabled with a `title`/description, no extra banner; the upload submit is disabled with
  its files kept; an e2e journey for it is in scope. Test list proposed.
- 2026-10-31: the user approved the test list as written; status set to spec-approved.
- 2026-10-31: cycle 1, test list item 1 (the user chose it first): "Reset name" is offered only
  while the stored name is not the default. The machine's `RESET` gained a `notDefault` guard
  (`stored !== "Speaker " + (speakerId + 1)`) and the button binds to
  `state.can({ type: 'RESET' })`, so the guard is the single source. Red-first in
  `TaskTranscriptView.test.tsx` (the button was enabled at the default); the duplicated rename
  MSW mock became `mockSpeakerRenames()` in an approved `Refactor:` commit.
- 2026-10-31: cycle 2, test list item 5 (the user chose it next): a request that never reaches the
  service becomes `ApiError('0', "Not saved: can't reach the app's service. Reconnecting…")` at
  the client boundary. Every fetch goes through `request()`, and the upload path's
  `xhr.onerror` carries the same message; server answers keep the server's message. Red-first in
  `TaskTranscriptView.test.tsx` (the panel printed the browser's `NetworkError…`). The approved
  `Refactor:` commit collapsed every JSON endpoint into `requestJson<T>()`.
- 2026-10-31: cycle 3, test list item 2 (the user chose it next): one `useWriteControl()` on
  `ConnectionContext` (true only in `live`) gates every write control, which names
  "Can't reach the app's service. Reconnecting…" in its `title` (the header's "Reconnecting" stays
  the banner). Applied to the settings fields, restore and fallback dismissal; the speaker name,
  reset, merge and reset merges (with the merge confirmation); segment save and revert; the upload
  zone and file input; queue pause, reorder (drag handle and menu moves), pause/resume/cancel, row
  delete and its confirmation; history retry and delete; the transcript retry and delete. "Reassign"
  is item 73's mode and is not on this branch; item 73 gets the same hook when it lands. Red-first in
  the new `writeGate.test.tsx` (the speaker field was enabled while reconnecting); the queue's upload
  tests now wait for the snapshot, since the spec makes uploads wait for the connection. The
  approved `Refactor:` commit derived the queue's empty state from the same hook; the branch was
  rebased onto `main` (5828afa4) and re-verified after the cycle.
- 2026-10-31: cycle 4, test list item 3 (the user chose it next): a regression pin, green on its
  first run — with the stream dropped, the queue's pending list is shown, scrolling still compacts
  the header, History leads into a transcript whose turns render, the loaded audio plays, and the
  settings page shows its stored values. No refactoring suggested or approved.
- 2026-10-31: cycle 5, test list item 4 (the user chose it next): a reactivity pin, green on its
  first run — after the dropped stream, the EventSource's own reconnect delivers a snapshot and
  the queue's drop zone, pause switch, reorder handles and cancel control are enabled again on the
  same mounted page (no reload). No refactoring suggested or approved.
- 2026-10-31: cycle 6, test list item 6 (the user chose it next): a regression pin, green on its
  first run — a 409 rename answers with the server's message in the speakers panel, and none of the
  unreachable-service wording. The backend's `gradle check` and the full journey suite are run at
  the branch's end with the e2e item. No refactoring suggested or approved.
- 2026-10-31: cycle 7, test list item 7 (the user chose it, the last item):
  `e2e/tests/87-writes-wait-for-the-connection.spec.ts` uploads jfk-b.wav when it is missing, opens
  its transcript, kills the real backend with `stopBackend`, sees "Reconnecting…" with the turns
  still readable and the name field, queue drop zone and pause switch disabled with
  "Can't reach the app's service. Reconnecting…", starts a fresh process on the same socket and
  data directory, sees the controls enabled and the rename go through on the same page. Passed on
  the real backend (43–51 s; run again after the refactor). The approved `Refactor:` commit
  extracted the handover (stop, restart, save the pid) from `13-restart.spec.ts` into
  `handOverSharedBackend()`. Proposal point 5 was done in the same stretch: the two rules are now
  in `docs/specs/frontend-state-and-errors.md` under "Writes and the connection".
- 2026-10-31: all seven test-list items are green; status set to in-review. Final verification on
  the rebased branch: frontend 274 tests in 38 files, `tsc` and prettier clean; backend
  `gradle check` BUILD SUCCESSFUL; e2e prettier, `tsc` and the nine `node --test` files green, and
  the new journey green against the real backend (run twice, 43-51 s); docs `tsc`, prettier and
  every check plus the 30 `node --test` assertions green — the diagram check's task-list arrow now
  pins `requestJson<TaskList>('/api/tasks')` after the client refactor. The full journey suite was
  not run end to end in this environment; the new journey and the package's unit checks are the
  e2e evidence.
- 2026-10-31: root review after in-review, with the user. The amendment above is approved (resend
  from the start, the usual duplicate notice, the drop zone and picker stay disabled; its seven
  items are written as given). The review's findings: (1) MEDIUM — every rejected fetch currently
  says "Not saved", reads included, and the error lines double the prefix; reads get "Can't reach
  the app's service. Reconnecting…", writes keep "Not saved: …", and rule 2 gains the distinction
  (tracked as the review-finding item above); (2) the amendment's upload behavior — `uploadBatchMachine`
  takes the connection as an input and gains a waiting state, `uploadTask` gains `xhr.abort`, a row
  cut off at the transport returns to queued with cleared progress, no per-request timeout (the
  stream's return decides), and a duplicate-audio resend uses `markDuplicate`; (3) LOW — the e2e
  journey's `stopBackend` sits outside try/finally and gets moved inside, with the finally bringing
  the backend back; (4) for the merge, this branch does not gate item 73's Reassign mode — whoever
  lands second applies `useWriteControl()` to its entry button and Move/confirmation and extends
  rule 1 of the spec. Status back to in-progress.
- 2026-10-31: review-finding cycle (the user chose it first): a rejected transport now says
  "Can't reach the app's service. Reconnecting…" for a read and keeps "Not saved: …" for a write;
  the client boundary decides by method (GET is a read). `failureMessage()` leaves the app's
  transport message alone instead of prefixing "Retry failed:"/"Delete failed:" (HistoryRow and the
  failed task's status page), and rule 2 of the spec records the split. Red-first in
  `writeGate.test.tsx`. No refactoring suggested or approved.
- 2026-10-31: amendment cycle 1 (the user chose the amendment's first item):
  `uploadBatchMachine` takes `{ live }` as input and gains a `waiting` state; `QueueView` forwards
  the write gate as `CONNECTION`. A transport failure (or a drop while a row uploads) returns the
  row to `queued` with cleared progress and its request is aborted through `uploadTask`'s new
  `AbortSignal`; the row is sent again from the start when the stream is live, with no error row
  and no per-request timeout. Red-first in `writeGate.test.tsx`, which covers both a first request
  that fails outright and one that hangs. The approved `Refactor:` commit extracted
  `isTransportError()` for the upload actor and `failureMessage()`.
- 2026-10-31: amendment cycle 2 (the user chose the next item): a batch pin, green on its first
  run — the first request hangs, the drop stops the batch, both rows are queued, no request starts
  while the stream is down, and the reconnect sends the cut-off row and then the row behind it.
  The approved `Refactor:` commit shared the upload-row test helpers.
- 2026-10-31: amendment cycle 3 (the user chose it): a pin, green on its first run — a resend
  answered `409 duplicate-audio` shows "Already uploaded — open" naming task 3 (`/history/3`),
  never an error row. The branch was rebased onto `main` (`2d07bb67`, item 73 merged) at the
  user's request: the conflicts in `SpeakersPanel.tsx` and the transcript's imports were
  resolved; per the merge note, item 73's reassign entry button, Move and confirmation now read
  `useWriteControl()`, rule 1 of the spec names them, and a test pins their disabled state. The
  full suite (288 tests), `tsc` and prettier are green after the rebase (one loaded run timed out
  two unrelated first tests; both pass alone and the rerun is green). No refactoring suggested or
  approved.
- 2026-10-31: amendment cycle 4 (the user chose it): a pin, green on its first run — a 400
  ("Unsupported language") stays on the row as the server's message through a drop and a
  reconnect, and no second request goes out. No refactoring suggested or approved.
- 2026-10-31: amendment cycle 5 (the user chose it): a pin, green on its first run — a waiting
  row, then leaving the queue and returning: no upload rows, `localStorage` and `sessionStorage`
  empty, no second request. No refactoring suggested or approved.
- 2026-10-31: amendment cycle 6 (the user chose it): the e2e journey gains "an upload cut off
  mid-request waits and finishes after the backend returns". The backend's detached group is
  frozen (`SIGSTOP`), the new `jfk-upload-wait.wav` clip is dropped so its request hangs, the group
  is killed (`SIGKILL`), the row waits (queued, no error), and a fresh backend on the same socket
  and data directory finishes the upload without a reload — accepting the usual duplicate notice
  when the cut-off attempt had in fact landed. Review finding 3 is fixed: both journeys open their
  `try` right after the stop, and the `finally` hands a fresh backend over through
  `handOverSharedBackend()` (which now stops whatever pid was started). The mid-upload test skips
  on Windows (no `SIGSTOP`). Passed on the real backend (2.0 m, both journeys). No refactoring
  suggested or approved.
- 2026-10-31: amendment cycle 7 (the user chose it, the last item): rule 2 of
  `docs/specs/frontend-state-and-errors.md` records the one upload exception — a row cut off by
  the drop waits queued, is re-sent in full when the live stream returns, and the batch waits with
  it; no per-request timeout (the reconnect decides); a duplicate resend shows the usual notice, a
  real refusal keeps the server's message and is never re-sent; no other write is retried.
- 2026-10-31: all amendment items and the review finding are green; status set to in-review.
  Rebased onto `main` (`2d07bb67`, item 73 merged) at the user's request and verified:
  frontend 290 tests in 38 files with `tsc` and prettier clean; e2e prettier, `tsc` and the nine
  `node --test` files green, and both journeys in
  `e2e/tests/87-writes-wait-for-the-connection.spec.ts` green on the real backend (2.0 m);
  docs `tsc`, prettier and every check plus the 30 `node --test` assertions green (169 code names,
  the diagram's 10 arrows); backend `gradle check` BUILD SUCCESSFUL (3m43s). The full journey suite
  was not run end to end in this environment; the item's journeys and the package checks are the
  e2e evidence.
- 2026-10-31: second root review after in-review (the user), four items below. (1) MEDIUM: a
  transport error while the stream is live strands the batch in `waiting`, which only leaves on a
  `CONNECTION` live flip; stay waiting, but after a 2–3 s grace with the stream still live publish
  the row's error with the app's message, and a real drop still waits and resends; rule 2 says the
  wait ends when the stream stays live. (2) MEDIUM: the e2e freezes the shared backend outside its
  `try`, so a failure leaves the group `SIGSTOP`ped for later specs; freeze inside `try` and
  `SIGKILL` the group in the `finally` before the handover. (3) LOW: the mid-upload journey skips
  on Windows; record it in the item and rule 2. (4) LOW: a waiting row shows the internal "queued";
  show "Waiting for the connection…". The full journey suite was green locally (57 passed,
  1 skipped, 10.8 m). Status back to in-progress.
- 2026-10-31: second-review cycle 1 (the user chose it): a transport error with the stream live no
  longer strands the batch. The cut-off row requeues into `waiting`, and its guarded `after` fires
  `STALL_GRACE_MS` (2.5 s) later: `markStalled` makes it an error with the app's message and the
  batch behind it continues. A real drop arrives within the grace and the guarded timer is
  dropped, so the row still waits and is re-sent; the existing cut-off tests stay green.
  Red-first in `writeGate.test.tsx`. Rule 2 now says the wait ends when the stream stays live.
  The approved `Refactor:` commit named `STALL_GRACE_MS`. One loaded suite run timed out an
  unrelated first transcript test; both files pass alone and the rerun was green (291 tests).
- 2026-10-31: second-review cycle 2 (the user chose it): a queued upload row while the batch is in
  `waiting` now says "Waiting for the connection…", never the internal "queued"; queued rows behind
  an active upload keep "queued". The upload tests and the e2e journeys assert the app's wording.
  Red-first in `writeGate.test.tsx`; the full suite (292 tests) and both journeys on the real
  backend (2.5 m) are green. No refactoring suggested or approved.
- 2026-10-31: second-review cycle 3 (the user chose it): the mid-upload journey's freeze now sits
  inside its `try`, and the `finally` `SIGKILL`s the frozen group (unless the journey already
  killed it) before `handOverSharedBackend()`, so a failure in the upload or the row assertion
  cannot leave a `SIGSTOP`ped backend for every later spec. Both journeys green on the real
  backend (1.7 m). No refactoring suggested or approved.
- 2026-10-31: second-review cycle 4 (the user chose it, the last): the amendment's e2e item records
  that the mid-upload journey is skipped on Windows (freezing needs POSIX signals), and rule 2
  records the skip as a coverage gap while the Windows e2e still gates merges. Documentation only.
- 2026-10-31: all four second-review items are green; status set to in-review. Final verification:
  frontend 292 tests in 38 files with `tsc` and prettier clean; e2e prettier, `tsc` and the nine
  `node --test` files green, and both journeys in
  `e2e/tests/87-writes-wait-for-the-connection.spec.ts` green on the real backend (1.7 m); docs
  `tsc`, prettier, every check and the 30 `node --test` assertions green; backend `gradle check`
  BUILD SUCCESSFUL. The full journey suite was not run end to end here; the journeys and package
  checks are the e2e evidence.
- 2026-10-31: red on CI at PR head `fcfbe29a` (runs 20:44–20:58). The three frontend jobs (Linux,
  macOS and Windows "pnpm test, pnpm build") fail on an unhandled error, identical on all three:
  `TypeError: xhr.abort is not a function` at
  `AbortSignal.signal.addEventListener.once src/shared/api/client.ts:216:48`, from
  `Object.dispose src/features/queue/uploadBatchMachine.ts:122:20`, originated in
  `src/features/queue/QueueView.test.tsx`. Vitest reports 38 files / 292 tests passed but
  "Errors 2 errors" and exits 1; reproduced locally the same way. The upload actor's cleanup calls
  `controller.abort()` on every unmount (after each uploading test), and the fakes `FakeXhr`
  (`QueueView.test.tsx:341`) and `StalledXhr` (`:428`) have no `abort()`. Reopened: the branch was
  reset to `roadmap/87-pre-squash` (`936dad9e`) and the item file restored to `roadmap/`; the e2e
  and docs jobs were green. The two discovered items are above.
- 2026-10-31: CI-finding cycle 1 (the first discovered item): the abort listener now comes off as
  soon as the upload settles (`onload`, `onerror`, `onabort`), so a later teardown never calls
  `xhr.abort` on a finished request, while a pending request still aborts exactly once. Red-first
  in `client.test.ts` (the settled fake was aborted by the later teardown). The suite's remaining
  unhandled error is a pending fake without `abort()` — the second discovered item. No refactoring
  suggested or approved.
- 2026-10-31: CI-finding cycle 2 (the second discovered item): the QueueView fakes abort through one
  shared `TestXhr` base whose `abort()` fires `onabort` (the progress fake, the stalled fake and a
  new held fake), and a new test unmounts the queue mid-upload and pins that the actor cleanup
  aborts the request once while the rejection is swallowed. The full frontend suite now exits 0
  with no "Unhandled Errors" block (294 tests). The approved `Refactor:` commit shared the fake
  base.
- 2026-10-31: the two CI-finding items are green; status set to in-review. Verification, by exit
  code: frontend `vitest run` 0 (38 files, 294 tests, no "Unhandled Errors" block), frontend `tsc` 0,
  frontend prettier 0, frontend `vite build` 0; e2e prettier 0, e2e `tsc` 0, the nine e2e
  `node --test` files 0; docs `tsc` 0, docs prettier 0, every docs check 0, the docs `node --test`
  files 0; the full journey 0 (57 passed, 1 skipped, 9.4 m). The root agent re-prepares the PR.
- 2026-10-31: red on CI's Linux e2e on the PR head: the mid-upload journey fails at
  `e2e/tests/87-writes-wait-for-the-connection.spec.ts:115` with
  "expect(locator).not.toContainText failed … element(s) not found". The `toPass` loop accepts the
  row being gone (the resend succeeded and the row left the uploads list), but the trailing
  `not.toContainText` still requires the element, so it fails whenever that branch wins; the
  duplicate notice keeps the row, which is why it was green locally. Windows e2e is red on a
  different, pre-existing test (`e2e/lib/backend.test.ts:100`, the 5 s wall-clock bound, which item
  84's branch already replaces) — not touched here. Reopened: reset to
  `roadmap/87-pre-squash` (`7366cc6f`) and status set to in-progress; the discovered scenario is
  above.
- 2026-10-31: journey-fix cycle (the discovered item): the mid-upload journey now reads the row
  once inside its `toPass` loop and, in that single reading, asserts it never carries "Not saved"
  and is either gone (the resend landed) or shows the usual duplicate notice; the trailing
  `not.toContainText` that required the element is gone. Red reproduced locally by failing the
  first upload at the transport so the resend lands: the old spec failed at the trailing line with
  "element(s) not found". Verified by exit code: the row-gone branch 0, the duplicate branch 0
  (forced with a pre-upload plus the routed first attempt), and the normal run 0; e2e prettier,
  `tsc` and the nine `node --test` files 0; the full journey 0 (57 passed, 1 skipped, 11.5 m);
  frontend `vitest run` 0 (294 tests), `tsc` 0 and prettier 0. No refactoring.
- 2026-10-31: integration pass on item 84's harness (rebase onto `main` `dac3e7f6`). The rebase
  replayed the item's history; conflicts were only in `QueueView.tsx` (the import block: item 84's
  `finishedStatus`/`pendingStatus` and `Settings` kept, `useWriteControl` added) and
  `13-restart.spec.ts` (adopted item 84's imports and its inline stop/start/`saveState` handover).
  The 87 spec now imports `test`/`expect` from `helpers/test`, so the automatic queue reset runs
  before each of its tests; its frozen upload sets the file input directly (item 84's `uploadClips`
  waits for one 201 per file and would time out against the frozen backend), with the row's
  appearance — not a timer — as the signal; every other wait uses item 84's helpers
  (`ensureTranscript`, `waitForTaskState`). `freezeBackend`/`killFrozenBackend`/
  `handOverSharedBackend` stay in `lib/backend.ts` on item 84's module, and the journey's `finally`
  SIGKILLs a frozen group before handing over and saving the fresh pid. Gate by exit code:
  `gradle check` 0; frontend `vitest run` 0 (297 tests, no unhandled errors), `tsc` 0, prettier 0,
  `vite build` 0; e2e `tsc` 0, prettier 0, `node --test` 0; docs `tsc` 0, prettier 0, every check 0,
  `node --test` 0; the full journey 0 (57 passed, 1 skipped, 9.4 m, both 87 journeys included).
  Locally only, the sandbox has no `/bin/sh`, so item 84's pnpm build cannot run there: the UI was
  built with the direct node commands and its digest recorded before the journey; the repo's build
  path is untouched.
- 2026-11-02 (root agent, PR preparation): approved after the item 84 integration. Rebased onto
  `main` (`dac3e7f6`, item 84 merged); `e2e/lib/backend.ts` against `main` is purely additive (the
  outage clip and `freezeBackend`/`killFrozenBackend`/`handOverSharedBackend`), item 84's state
  guard and health probe are intact, both journeys import item 84's `test` (the queue reset runs
  before them), and the frozen upload drives the file input directly because item 84's `uploadClips`
  waits for a 201 a cut-off request never gets; every other wait uses `ensureTranscript`/
  `waitForTaskState`. Re-ran the whole gate and read every exit code: `gradle check` 0, frontend
  `vitest run` 0 (38 files, 297 tests, no "Unhandled Errors"), `tsc` 0, prettier 0, `vite build` 0;
  e2e `tsc` 0, prettier 0, `node --test` 0 (40); docs `tsc` 0, prettier 0, links and every check 0,
  `node --test` 0 (33); the item's spec alone 0 (2 passed, 1.2 m) and the full journey 0 (57 passed,
  1 skipped, 9.1 m). The item's spec and final test list moved from
  `roadmap/87-writes-wait-for-the-connection.md` to `docs/specs/writes-and-the-connection.md` with
  `Status: done`, the README row and graph node, and item 78's gate link and row; the branch is
  squashed to one commit on `main` (ADR 0050). The write rules themselves stay in
  `docs/specs/frontend-state-and-errors.md`.
