# 72 — Frontend state and error handling: visible errors, one settings owner, validated input

Status: spec-approved
Depends on: —
Blocks: [78](78-stable-release.md) (the release gates depend on this item)

## Goal

No frontend action fails silently, and no two live copies of the same state disagree: a rejected
pause or resume says so, a failed settings load can be retried, the threads field cannot store a
value other than the one typed nor be submitted twice, one settings instance serves every view, the
live connection's per-task maps stay bounded, and the MSW handlers serve every settings endpoint the
contract has.

## Evidence

Line numbers are `main` after item 81.

- **Pause and resume failures are silent.** `queuePauseMachine` stores the failure in its context
  (`frontend/src/features/queue/queuePauseMachine.ts:12,36-38`) and nothing reads it: `QueueView`
  renders only the reorder error (`frontend/src/features/queue/QueueView.tsx:418-419`), and the
  switch shows the connection's `paused` flag (`:393-398`), so a rejected pause or resume flips back
  with no message.
- **A failed settings load is unrecoverable.** `settingsMachine` declares `failed` with a `RETRY`
  transition (`frontend/src/shared/state/settingsMachine.ts:95-96`), but
  `frontend/src/features/settings/SettingsView.tsx:136` renders the callout without an `action`,
  though `frontend/src/shared/ui/ErrorCallout.tsx` accepts one. While no settings are stored the form
  renders empty fields and Save stays enabled (`:209`), so a click would PUT an empty device.
- **The threads field stores a value other than the one typed, and a save can be submitted twice.**
  `frontend/src/features/settings/SettingsView.tsx:111` parses with `Number.parseInt`, so `1.5` is
  sent as 1 with no error. The contract's bound is an integer `>= 1` with no upper bound
  (`docs/api/openapi.yaml:572`; `backend/src/main/kotlin/stt/http/SettingsRoutes.kt:125` rejects only
  `< 1`). Junk typed into a `type="number"` field sanitizes to `""`, which is the contract's
  legitimate `null` (whisper's default), and `-3`/`0` reach the server and are rejected inline, so
  the earlier "NaN on the wire" reading does not reproduce. Save is disabled only while dismissing
  (`:209`), so a second click during a pending PUT sends a second PUT.
- **Settings have one owner per view, not per app.** `SettingsView.tsx:55` and `QueueView.tsx:198`
  each run their own `settingsMachine`. The routes are disjoint, so every navigation fetches
  `GET /api/settings` again; both views carry the same `settingsUpdate` bridge
  (`SettingsView.tsx:66-70`, `QueueView.tsx:235-239`); and a pending dismiss or a rejection dies with
  the unmount.
- **The connection machine's per-task maps never shrink.** `speakerChanges`
  (`frontend/src/shared/state/connectionMachine.ts:106-120`) and `segmentEdits` (`:122-131`) keep one
  entry per task id: `task-deleted` does not drop them, and a `snapshot` (`:45-52`) keeps entries for
  tasks it no longer lists. `settingsUpdate` is a single latest value, not a map (the filing said
  "unboundedly" of both).
- **MSW serves no settings write.** `frontend/src/shared/api/handlers.ts:193` registers only
  `GET /api/settings`; `PUT` is registered per test (`SettingsView.test.tsx:55-60`), so `pnpm
  dev:mock` errors on save and on dismissing the fallback notice, while the backend serves
  `PUT /api/settings` and `DELETE /api/settings/device-fallback` (`docs/api/openapi.yaml:362-396`).
- **The filing's "stale merge replay" does not reproduce.** `turnsMachine` handles `RELOAD` only in
  `loaded` (`frontend/src/features/history/turnsMachine.ts:73`), and on a fresh mount the `LOAD`
  effect (`TaskTranscriptView.tsx:178-184`) dispatches first, so the speakers effect's `RELOAD`
  (`:190-201`) lands in `idle`/`loading` and is ignored — a transcript whose last speakers event was
  a merge fetches `/turns` once. The `id`-change path that could replay it is unreachable: `/history`
  unmounts the view. The test list pins that behavior instead of fixing a non-bug. The genuine
  neighbouring gap — a speakers event that arrives while `/turns` is loading is lost — stays in
  `docs/known-issues.md:34` and is not this item.

## Proposal

1. **Pause and resume errors are shown.** The failure renders in an `ErrorCallout` below the switch
   row, the app's one error style and an `alert`: the title names the attempted command ("Could not
   pause the queue" / "Could not resume the queue"), which the machine keeps as the attempted
   `paused` value, and the detail is the server's message. The machine's existing `TOGGLE` clears it,
   so the message lasts until the next command; the switch itself is the retry. The reorder error
   line (`QueueView.tsx:418-419`) stays as it is.
2. **A failed settings load is retryable.** The settings error callout carries a Retry action that
   sends the machine's `RETRY`; the existing `failed -> loading` transition clears the error and
   fetches again. Save is disabled whenever no settings are stored, so a failed or pending load can
   never PUT partial data.
3. **Threads are validated before they are sent.** The field accepts an integer `>= 1`, and an empty
   field means `null` (whisper's default, as the contract has it). Invalid input shows a field error
   (a local danger-text line, `aria-invalid` and `aria-describedby` on the input; the shared `Field`
   is unchanged) and disables Save, so no PUT leaves with a value the contract rejects. The settings
   machine gains a `saving` state, which disables Save while a PUT is in flight and drops a second
   click. `clampCount`'s silent clamping of the speaker counts is a different, tested convention and
   is left alone.
4. **One settings instance for the app.** `settingsMachine` runs once, in a
   `frontend/src/app/SettingsContext.tsx` created with `createActorContext` and provided by `App`
   beside `ConnectionContext` — one interpreter of the same deterministic machine, the pattern the
   connection already uses (ADR 0038 keeps holding: no React state copy). A single `SettingsSync`
   component inside both providers forwards the connection's `settingsUpdate` as `UPDATED`, replacing
   the two view-local bridges. `SettingsView`, `QueueView` and the fallback banner read that
   instance through its context and actor ref — no prop drilling, no module singleton (a singleton
   would break per-test isolation). The app therefore fetches `GET /api/settings` once per load, and
   a save or a dismissal survives navigation.
5. **The connection's per-task maps stay bounded.** `task-deleted` drops the task's `speakerChanges`
   and `segmentEdits` entries, and a `snapshot` keeps only the entries of the tasks it lists. No LRU
   or ring buffer: an entry is recreated by the task's next event, and the state only ever holds
   entries for tasks it carries.
6. **MSW serves the settings endpoints the contract has.** `handlers.ts` registers
   `PUT /api/settings`, which echoes the sent update over the fixture's fetch-only fields
   (`availableDevices`, `gpuNames`, `deviceProbeError`) with the fallback notice cleared as the
   contract has it, and `DELETE /api/settings/device-fallback`, which answers with the notice
   cleared. Both are "Mock addition" comments like the existing ones; `GET` keeps serving
   `settings.json`, since the mocks store nothing (the segments precedent). Validation stays the
   server's job — the view shows its rejection inline.

## Decisions (consultation 2026-10-27)

- **Shared actor's shape**: one `createActorContext` provider owned by the app shell, exactly like
  `ConnectionContext`. Views read `SettingsContext.useSelector` / `useActorRef()`. Rejected: a module
  singleton.
- **Cap/prune policy**: drop a task's entries on `task-deleted` and on any `snapshot` that no longer
  lists it. Bounded by the tasks the state holds.
- **Retry scope**: `GET /api/settings` only. The SSE stream has its own watchdog reconnect
  (ADR 0044); restarting it from a settings Retry would discard the live queue state.
- No new ADR: the actor pattern (ADR 0038) and the contract-first examples (ADR 0029) already cover
  these decisions.

## Test list

- [ ] A failed queue pause shows the server's error beside the pause control, and the switch returns
      to the queue's real (unpaused) state
- [ ] A failed queue resume shows its error the same way, and the next command clears it
- [ ] A failed settings load shows an error with Retry; Retry fetches again and the form shows the
      stored settings, with Save usable again
- [ ] While no settings are stored (failed load), Save is disabled and no PUT is issued
- [ ] Invalid threads input (0, negative, a fraction, pasted junk) shows a field error and disables
      Save; the stored threads value is unchanged
- [ ] Empty threads saves `null` (whisper's default) and is not an error
- [ ] A save in flight disables Save; a second click sends no second PUT; Save returns when the save
      settles, successfully or with a rejection
- [ ] Every view reads one shared settings instance: `/queue` then `/settings` in one app load issue
      a single `GET /api/settings`
- [ ] A save made in Settings is visible in the Queue without a second fetch
- [ ] A failed settings load is shared: navigating between the two views issues no new GET, and
      Retry in either recovers both
- [ ] The connection's per-task maps hold only tasks the state holds: `task-deleted` drops a task's
      speaker and segment entries, and a snapshot drops entries for tasks it no longer lists (many
      events, bounded result)
- [ ] A transcript whose last speakers event was a merge fetches `/turns` once on opening (the stale
      merge is not replayed)
- [ ] The shared MSW handlers save settings and answer the stored settings; the queue banner dismisses
      the device-fallback notice through them — no per-test override
- [ ] (must not break) The frontend suite, `pnpm build`, `pnpm dev:mock` and the e2e journey stay green

## Acceptance criteria

- No frontend action fails silently; settings has one owner with retry and validated input; the
  connection's per-task maps are pruned; the MSW handlers serve the settings endpoints the contract
  has.
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
