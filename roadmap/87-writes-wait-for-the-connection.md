# 87 — Writes wait for the connection, and a control with nothing to do says so

Status: spec-approved
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

- [ ] (ui) "Reset name" is disabled while the stored name is the default (`Speaker N+1`) and enabled
      after a rename; a reset leaves it disabled again, and no request is sent while it is disabled
- [ ] (ui) With the connection not live, every control that writes is disabled — settings save, reset
      and dismiss; rename, reset name, reassign, merge and reset merges; segment edit and revert;
      upload submit; queue pause, reorder, cancel and retry; task delete — each naming why in its
      `title`/description
- [ ] (ui) Reading keeps working while the connection is not live: the queue, a transcript, its
      turns and the settings page are shown, scrolling works, loaded audio plays, pages switch
- [ ] (ui) When the connection is live again the same controls are enabled, without a reload
- [ ] (app) A write whose request fails at the transport shows "Not saved: can't reach the app's
      service. Reconnecting…", never the browser's `NetworkError…` text, on every control that shows
      a write error
- [ ] (must not break) A rejected write (`400`, `404`, `409` with a server message) still shows the
      server's message, and the existing frontend, backend and journey suites stay green
- [ ] (e2e, ADR 0046) A journey stops the backend, sees the write controls disabled and the
      transcript still readable, restarts it, and sees a rename go through

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
