# 72 — Frontend state and error handling: visible errors, one settings owner, auto-save with a restore-defaults reset

Status: done (roadmap item 72, approved 2026-10-28)
Depends on: —
Blocks: [78](../../roadmap/78-stable-release.md) (the release gates depend on this item)

## Goal

No frontend action fails silently, and no two live copies of the same state disagree: a rejected
pause or resume says so, a failed settings load can be retried, the settings fields save themselves
without a Save button and never store a value other than the one typed, a "Restore default settings"
action behind a confirmation puts every field back to the server's defaults, one settings instance
serves every view, the live connection's per-task maps stay bounded, and the MSW handlers serve every
settings endpoint the contract has.

## Evidence

Line numbers are `main` after item 82.

- **Pause and resume failures are silent.** `queuePauseMachine` stores the failure in its context
  (`frontend/src/features/queue/queuePauseMachine.ts:12,36-38`) and nothing reads it: `QueueView`
  renders only the reorder error (`frontend/src/features/queue/QueueView.tsx:418-419`), and the
  switch shows the connection's `paused` flag (`:393-398`), so a rejected pause or resume flips back
  with no message.
- **A failed settings load is unrecoverable.** `settingsMachine` declares `failed` with a `RETRY`
  transition (`frontend/src/shared/state/settingsMachine.ts:95-96`), but
  `frontend/src/features/settings/SettingsView.tsx:136` renders the callout without an `action`,
  though `frontend/src/shared/ui/ErrorCallout.tsx` accepts one. While no settings are stored the form
  renders empty fields and stays editable (`:209`), so a write could carry an empty device.
- **The settings form stores values other than the ones typed, and writes can pile up.**
  `frontend/src/features/settings/SettingsView.tsx:111` parses `threads` with `Number.parseInt`, so
  `1.5` is sent as 1 with no error (the contract's bound is an integer `>= 1`, no upper bound:
  `docs/api/openapi.yaml:572`, `backend/src/main/kotlin/stt/http/SettingsRoutes.kt:125`), and
  `clampCount` (`:40-46`) silently coerces the speaker counts into `1..20` while ignoring an empty
  field. Junk typed into a `type="number"` field sanitizes to `""`, which is the contract's
  legitimate `null` (whisper's default), and `-3`/`0` reach the server and are rejected inline, so
  the earlier "NaN on the wire" reading does not reproduce. Save is disabled only while dismissing
  (`:209`), so a second click during a pending PUT sends a second PUT.
- **The settings have a Save button, and the contract has no reset.** The page writes only on Save,
  and nothing in the contract means "restore the defaults": the defaults exist as the V6 seed
  (`backend/src/main/resources/db/migration/V6__settings.sql`) and as
  `docs/api/settings-updated.defaults.json` (auto, `null`, auto, auto, `null`, 8), and
  `docs/api/openapi.yaml:362-396` has `GET`, `PUT` and the fallback dismissal only.
- **Three e2e journeys own the Save button.** `e2e/tests/17-settings.spec.ts:51`,
  `e2e/tests/18-macos-metal.spec.ts:17,29` and `e2e/desktop-tests/helpers/app.ts:157` click the
  Settings page's Save, so an interaction change rewrites them (ADR 0046: the feature's e2e follows
  the feature).
- **Settings have one owner per view, not per app.** `SettingsView.tsx:55` and `QueueView.tsx:198`
  each run their own `settingsMachine`. The routes are disjoint, so every navigation fetches
  `GET /api/settings` again; both views carry the same `settingsUpdate` bridge
  (`SettingsView.tsx:66-70`, `QueueView.tsx:235-239`); and a pending write or a rejection dies with
  the unmount.
- **The connection machine's per-task maps never shrink.** `speakerChanges`
  (`frontend/src/shared/state/connectionMachine.ts:106-120`) and `segmentEdits` (`:122-131`) keep one
  entry per task id: `task-deleted` does not drop them, and a `snapshot` (`:45-52`) keeps entries for
  tasks it no longer lists. `settingsUpdate` is a single latest value, not a map.
- **MSW serves no settings write.** `frontend/src/shared/api/handlers.ts:193` registers only
  `GET /api/settings`; `PUT` is registered per test (`SettingsView.test.tsx:55-60`), so `pnpm
  dev:mock` errors on save and on dismissing the fallback notice.
- **The filing's "stale merge replay" does not reproduce.** `turnsMachine` handles `RELOAD` only in
  `loaded` (`frontend/src/features/history/turnsMachine.ts:73`), and on a fresh mount the `LOAD`
  effect (`TaskTranscriptView.tsx:178-184`) dispatches first, so the speakers effect's `RELOAD`
  (`:190-201`) lands in `idle`/`loading` and is ignored. The `id`-change path that could replay it is
  unreachable (`/history` unmounts the view). A test pins the behavior instead of fixing a non-bug;
  the genuine neighbouring gap (a speakers event that arrives while `/turns` is loading is lost)
  stays in `docs/known-issues.md:34` and is not this item.

## Proposal

1. **Pause and resume errors are shown.** The failure renders in an `ErrorCallout` below the switch
   row — the app's one error style, announced as an alert: the title names the attempted command
   ("Could not pause the queue" / "Could not resume the queue"), which the machine keeps as the
   attempted `paused` value, and the detail is the server's message. The machine's existing `TOGGLE`
   clears it, so the message lasts until the next command; the switch is the retry. The reorder error
   line stays as it is.
2. **A failed settings load is retryable, and the form is inert until settings exist.** The settings
   error callout carries a Retry action that sends the machine's `RETRY`; the existing
   `failed -> loading` transition clears the error and fetches again. While no settings are stored
   the fields are disabled, so a failed or pending load can never write a partial settings.
3. **The settings save themselves; there is no Save button.** Selects and switches commit on change.
   The typed fields — threads, speaker count, max speakers — commit when the input itself loses focus
   (focus moving to anything else, including another field or the restore action) or on Enter; typing
   never writes, so an intermediate `""` or `"-"` cannot be stored. Every commit is a whole-settings
   `PUT /api/settings`.
   - **Writes are serialized and coalesced**: at most one write in flight; commits made while it runs
     collapse into one follow-up with the latest values; the next starts when it settles. The restore
     request joins the same queue and waits behind the in-flight write.
   - **Invalid input is never sent**: a typed field whose value is not valid (see 4) shows its error
     and writes nothing; the field keeps what was typed until it is corrected.
   - **A rejected write puts the field its code names back to its last stored value** and shows the
     server's message there (`invalid-device`, `invalid-threads`, `invalid-language`,
     `invalid-speakers`, `invalid-max-speakers`; `SettingsRoutes.kt:121-141`). With one write per
     field change, that is the value the previous field's write stored. Every other field keeps its
     draft: the rejected snapshot is dropped, and the next write is built from the form as it stands
     when the writer is idle, so a value the rejected write also carried is stored then, and nothing
     stale is retried. A code naming no field uses the page's error callout and reverts no field.
   - **The page says "Saving…"** (a polite live region) while a write is in flight and drops it when
     the settings are stored; a failure replaces it with the field error or the callout.
   - **A `settings-updated` from another tab or the engine applies to the fields**, except the field
     that currently has focus (the user's draft is kept) and a field holding an invalid value (it
     keeps its error); the stored baseline always follows the event.
4. **Typed fields are validated before they are sent.** Threads: an integer `>= 1`, no upper bound
   (the contract's bound), with an empty field meaning `null` (whisper's default). Speaker count and
   max speakers: an integer `1..20` (the contract's bound; the silent `clampCount` coercion goes,
   since under auto-save it would store a value the user never typed). An invalid value shows a field
   error — a local danger-text line with `aria-invalid` and `aria-describedby` on the input, the
   shared `Field` unchanged — and writes nothing.
5. **One settings instance for the app.** `settingsMachine` runs once, in a
   `frontend/src/app/SettingsContext.tsx` created with `createActorContext` and provided by `App`
   beside `ConnectionContext` — one interpreter of the same deterministic machine (ADR 0038 keeps
   holding: no React state copy). A single `SettingsSync` component inside both providers forwards
   the connection's `settingsUpdate` as `UPDATED`, replacing the two view-local bridges.
   `SettingsView`, `QueueView` and the fallback banner read that instance through its context and
   actor ref — no prop drilling, no module singleton (a singleton would break per-test isolation). The
   app fetches `GET /api/settings` once per load, and a write or a dismissal survives navigation.
6. **"Restore default settings" resets through the server.** The action sits where Save was, opens the
   existing `ConfirmDialog`, and on confirmation sends `DELETE /api/settings`; the `200` replaces the
   stored settings and every field shows the defaults (device `auto`, threads empty, language
   auto-detect, speakers `auto`, max speakers 8). It also clears the CPU-fallback notice, because a
   reset writes the device again, and other tabs follow through `settings-updated`. Cancelling sends
   nothing. A failed reset shows the page's error callout and leaves the fields as they were.
7. **The contract and the backend gain the reset.** `DELETE /api/settings` resets the single settings
   row to the defaults in one write that clears `deviceFallback`, answers `200` Settings and emits
   `settings-updated` (the shape of the other writes: no body, no refusals, idempotent). The defaults
   are a Kotlin constant in `stt.db`, the same values V6 seeds, pinned by a route spec. The contract
   is edited in the same commit as its examples: `docs/specs/http-api-and-sse.md`, `docs/api/README.md`
   and `docs/api/openapi.yaml`; the reset reuses the existing `settings.json` shape, so no example file
   is added.
8. **The connection's per-task maps stay bounded.** `task-deleted` drops the task's `speakerChanges`
   and `segmentEdits` entries, and a `snapshot` keeps only the entries of the tasks it lists. No LRU
   or ring buffer: an entry is recreated by the task's next event.
9. **MSW serves the settings endpoints the contract has.** `handlers.ts` registers
   `PUT /api/settings`, which echoes the sent update over the fixture's fetch-only fields
   (`availableDevices`, `gpuNames`, `deviceProbeError`) with the fallback notice cleared as the
   contract has it; `DELETE /api/settings`, which answers the defaults with the notice cleared; and
   `DELETE /api/settings/device-fallback`, which answers with the notice cleared. All are "Mock
   addition" comments like the existing ones; `GET` keeps serving `settings.json`, since the mocks
   store nothing (the segments precedent). Validation stays the server's job — the page shows its
   rejection on the field.

## Decisions (consultation 2026-10-27)

- **Shared actor's shape**: one `createActorContext` provider owned by the app shell, exactly like
  `ConnectionContext`. Views read `SettingsContext.useSelector` / `useActorRef()`. Rejected: a module
  singleton.
- **Cap/prune policy**: drop a task's entries on `task-deleted` and on any `snapshot` that no longer
  lists it. Bounded by the tasks the state holds.
- **Retry scope**: `GET /api/settings` only. The SSE stream has its own watchdog reconnect
  (ADR 0044); restarting it from a settings Retry would discard the live queue state.
- **Auto-save (change request of 2026-10-27, the user)**: no Save button; selects and switches commit
  on change, typed fields on the input's own blur or Enter; one write in flight with coalescing; a
  rejected write reverts the form. Asked and answered: "blur" means the element's own loss of focus,
  so moving from one field to the next commits the first; and a rejected write reverts the field its
  code names to its last stored value — with a write per field change, that is the previous field's
  write — while every other changed value is written by the next write.
- **Defaults source (change request of 2026-10-27, the user)**: a new `DELETE /api/settings`; the
  server owns the defaults rather than the frontend hardcoding them (which would drift from V6).
- **Restore UX (change request of 2026-10-27, the user)**: confirmation first, through the existing
  dialog and confirm machine, then the reset.
- No new ADR for the actor/prune/Retry decisions (ADR 0038, ADR 0029 cover them). The interaction
  change has its own: [ADR 0062](../decisions/0062-settings-auto-save-and-default-reset.md).

## Test list

- [x] A failed queue pause shows the server's error beside the pause control, and the switch returns
      to the queue's real (unpaused) state
- [x] A failed queue resume shows its error the same way, and the next command clears it
- [x] A failed settings load shows an error with Retry; Retry fetches again and the form shows the
      stored settings
- [x] While no settings are stored (failed load), the fields are disabled and no write is sent
- [x] Choosing a device sends the whole settings at once, with no Save button on the page, and the
      page keeps showing the answer
- [x] A threads edit is not sent while typing; leaving the field (or Enter) sends the whole settings
      with the new count
- [x] Clearing the threads field and leaving it stores `null` (whisper's default)
- [x] Invalid threads or speaker-count input (0, negative, a fraction, pasted junk) shows a field
      error and is never sent; the stored value is unchanged
- [x] Two changes made before the first write settles collapse into one follow-up write carrying both
      (at most two writes, the second with the latest values)
- [x] A rejected write shows the server's message on the field its code names and puts that field back
      to its last stored value, changing nothing on the server; a change the rejected write also
      carried is stored by the next write
- [x] While a write is in flight the page says "Saving…", and it is gone once the settings are stored
- [x] Restore default settings asks first; confirming resets the stored settings and every field
      shows the defaults (auto, empty threads, auto-detect, auto speakers, 8 max speakers)
- [x] Cancelling the restore sends no request and changes nothing
- [x] The restore clears the CPU-fallback notice, and other tabs follow through `settings-updated`
- [x] Every view reads one shared settings instance: `/queue` then `/settings` in one app load issue
      a single `GET /api/settings`
- [x] A save made in Settings is visible in the Queue without a second fetch
- [x] A failed settings load is shared: navigating between the two views issues no new GET, and
      Retry in either recovers both
- [x] The connection's per-task maps hold only tasks the state holds: `task-deleted` drops a task's
      speaker and segment entries, and a snapshot drops entries for tasks it no longer lists (many
      events, bounded result)
- [x] A transcript whose last speakers event was a merge fetches `/turns` once on opening (the stale
      merge is not replayed)
- [x] The shared MSW handlers save, reset and answer the stored settings; the queue banner dismisses
      the device-fallback notice through them — no per-test override
- [x] (discovered) Switching the speakers mode to `fixed` with no valid count writes nothing until a
      valid count is typed; switching to `auto` or `off` writes with a null count
- [x] (discovered, review) A settings-updated from another tab or the engine keeps the focused
      field's draft and an invalid field's draft and error, and a queued follow-up is not flashed
      back to the previous answer
- [x] (discovered, review) A change made during a rejected write is sent once, rebuilt from the form
      with the rejected field reverted
- [x] (discovered, review) A write rejection that names no field shows its callout without a Retry
      action; only the failed load retries
- [x] (discovered, review) Dismiss waits while a settings write runs, the settings form and restore
      wait while a dismissal runs, and a failed dismissal says so beside the banner
- [x] (discovered, review) `fixed` without a valid count never sends `fixed` with a null count when
      another field changes; the stored speakers travel instead
- [x] (discovered, review) A refused paste's held draft is not committed on blur
- [x] (discovered, review) The reset's defaults are pinned to the fresh V6 seed, the contract docs
      list `DELETE /api/settings`, and the pause error and settings Retry have e2e journeys
- [x] (discovered, review) A write answer that a settings-updated superseded does not overwrite the
      event's settings: the CPU-fallback banner and the form's device keep the event's values
- [x] (discovered, review) A settings-updated keeps the message of a write rejection the user has
      not answered, on the field its code names
- [x] (discovered, review) A failed reset shows the server's message in the page callout with no
      Retry, leaves every field as it was, and re-enables Restore default settings
- [x] (discovered, review) A dismissal answer that a settings-updated superseded does not overwrite
      the event's settings: the fallback notice the event carried stays
- [x] (discovered, review) A settings-updated that arrives before the settings load answers is kept:
      the form and the fallback banner show the event's settings, not the older fetch answer
- [x] (discovered, review) A successful fallback dismissal does not clear a settings write rejection
      the user has not answered
- [x] (must not break) The frontend suite and `pnpm build` stay green, `pnpm dev:mock` saves and
      restores settings, and the three e2e journeys that clicked Save (`17-settings`,
      `18-macos-metal`, the desktop helper) are rewritten to the auto-save flow and stay green

## Acceptance criteria

- No frontend action fails silently; the settings save themselves and can be restored to the
  server's defaults behind a confirmation; settings have one owner; the connection's per-task maps
  are pruned; the MSW handlers serve the settings endpoints the contract has.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (silent pause error, dead settings retry,
  threads NaN, duplicated state, MSW drift); not yet discussed.
- 2026-10-27 (consultation, the user): accepted the corrected evidence — the threads defect is a
  silent truncation plus a double-submittable Save, not NaN on the wire; `settingsUpdate` is not a
  map, and `segmentEdits` was missing; the "stale merge replay" does not reproduce and becomes a
  regression pin instead of a fix. Decided: one XState actor for settings shared through
  `createActorContext` like `ConnectionContext` (not a module singleton); prune on `task-deleted`
  and on `snapshot`; Retry refetches `GET /api/settings` only; pause errors use `ErrorCallout` and
  last until the next command; Save disabled until settings are stored; threads validated as an
  integer `>= 1` with no upper bound; `clampCount` untouched. Test list approved as worded.
  Status: spec-approved.
- 2026-10-27 (change request, the user, before any cycle ran): the Settings page loses Save and every
  field auto-saves; a "Restore default settings" action with a confirmation replaces it. Decided with
  the user: `DELETE /api/settings` with the defaults owned by the server (not a frontend copy); typed
  fields commit on their own blur or Enter, selects and switches on change; one write in flight with
  coalescing; the restore asks first. Consequences recorded above: proposals 3, 4, 6, 7 and 9
  rewritten, the e2e journeys that clicked Save are rewritten (ADR 0046), item 32's Save flow is
  superseded ([ADR 0062](../decisions/0062-settings-auto-save-and-default-reset.md)). The two
  earlier "Save disabled" test-list items became the disabled-fields item, and the auto-save items
  were added. Status: spec-approved.
- 2026-10-27 (rebase onto the amended spec — Canon TDD §3 "push on", the user): eight commits made
  under the previous test-list wording were replayed onto the amended spec. Their ticks were re-keyed
  to the items that survive: the pause and the resume cycles (items 1 and 2), the failed-load Retry
  (item 3 — its test still asserts the Save button, and that clause goes in item 5's cycle), and the
  one settings instance (item 15). Three cycles are Save-bound and were un-ticked: "disable Save while
  no settings are stored", "reject invalid threads and block a junk paste" and "save an empty threads
  field as null"; their code stays, and items 4, 8 and 7 re-establish the behavior under the
  auto-save rules. `parseThreads` (a whole-number parse, never `parseInt`) and the shared
  `SettingsContext`/`SettingsSync` survive as they are. Item 5 (removing Save) comes before the
  commit mechanics, since items 6–11 drive the save trigger it introduces.
- 2026-10-28 (review, the root agent): changes requested and made on the branch — the sync effect
  keeps focused and invalid drafts and does not apply an answer that starts a queued follow-up; a
  rejected write with a queued change rebuilds that change from the form; Dismiss waits for a write
  and the form waits for a dismissal (no event is dropped); a failed dismissal is shown in the
  banner; Retry is offered only by the failed load; `fixed` with no valid count sends the stored
  speakers, so another field still stores; the reset spec pins the fresh V6 seed to
  `DEFAULT_SETTINGS`; the contract docs, the route comment and the duplicate-helpers issue are
  current; a refused paste is not committed on blur; and the pause error and settings Retry have
  e2e journeys. The new scenarios are ticked above as `(discovered, review)`. Status: in-review.
- 2026-10-28 (second review, the root agent): the three findings and the dismissal hole were fixed
  on the branch. A write answer superseded by a `settings-updated` no longer overwrites the event's
  settings (red-first, `bd5911b4`); a shown rejection survives a `settings-updated` (red-first,
  `3e5e83fa`); the failed reset is pinned to its page callout with no Retry and unchanged fields
  (passed on its first run — a regression pin, not a defect — `f376c928`); and the dismissal's
  answer is under the same guard, included on the user's approval in this review (red-first,
  `0559da98`). The freshness check was extracted into one helper (`Refactor:`, `693b4876`), and
  ADR 0062's Decision gained the dated ordering rule. The four items are ticked above; Status
  stays `in-review`.
- 2026-10-28 (PR preparation, the user's approval): the two remaining review notes were closed on the
  branch — a `settings-updated` that arrives before the settings load answers is laid over the fetch
  answer instead of dropped, and a successful dismissal no longer clears a write rejection the user
  has not answered; both red-first in `App.test.tsx`, and ADR 0062's ordering rule names the
  pre-load case. The user approved the diff and the PR preparation. Status: done.
