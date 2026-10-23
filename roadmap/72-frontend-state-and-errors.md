# 72 — Frontend state and error handling: visible errors, one settings owner, validated input

Status: draft
Depends on: —
Blocks: —

## Goal

The frontend never hides a failed action and never keeps two copies of the same state: pause/resume
errors are shown, a failed settings load can be retried, invalid threads input cannot overwrite the
stored value or double-submit, one shared settings actor serves every view, and the MSW handlers
match the API contract.

## Evidence

- **Pause/resume failures are silent (HIGH)**: `queuePauseMachine` stores the error, but `QueueView`
  renders only `reorder.context.error` (`frontend/src/features/queue/QueueView.tsx:418-419`); the
  toggle simply flips back with no message (`:398` disables it while sending).
- **Settings load failure is unrecoverable**: `settingsMachine` declares `failed` with a `RETRY`
  transition (`frontend/src/shared/state/settingsMachine.ts:95-96`), but `SettingsView` never sends
  `RETRY` — a failed `GET /api/settings` leaves a bare callout until remount.
- **Threads can silently corrupt the setting**: `threads: threads === '' ? null :
  Number.parseInt(threads)` (`features/settings/SettingsView.tsx:111`) turns non-numeric input into
  `NaN` → `null` on the wire, overwriting the stored value; the Save button is disabled only while
  dismissing (`:208-212`), so a pending PUT can be submitted again.
- **Two settings owners**: `SettingsView.tsx:55` and `QueueView.tsx:198` each instantiate their own
  `settingsMachine` (two unsynchronized GETs); `connectionMachine` accumulates `speakerChanges` and
  `settingsUpdate` unboundedly (`shared/state/connectionMachine.ts:55,109-116`); `TaskTranscriptView`
  replays a stale merge event into a redundant `/turns` refetch on every visit
  (`features/history/TaskTranscriptView.tsx:198-206`).
- **MSW drift**: `handlers.ts` registers `GET /api/settings` only
  (`frontend/src/shared/api/handlers.ts:165-167`), while the backend also serves `PUT /api/settings`
  and `DELETE /api/settings/device-fallback`; per-test `server.use` papers over it and `pnpm dev:mock`
  errors on save/dismiss.

## Proposal

1. Render `pause.context.error` beside the pause control (the reorder error's pattern) and keep it
   until the next successful command.
2. Wire the settings `RETRY` (a Retry action in the error callout) or delete the dead transition —
   retry is the user-visible behavior the machine already models.
3. Validate the threads field (numeric, 1–…; invalid input disables Save with a field error) and
   disable Save while a PUT is pending; never serialize `NaN` as `null`.
4. One shared settings actor (a provider beside the connection provider), with the unbounded maps
   capped/pruned and the stale merge replay removed in favor of the live event.
5. Add the missing MSW handlers from the `docs/api` contract so `pnpm dev:mock` matches the backend.

## Open questions

- The shared settings actor's shape (a React context owned by the app shell vs a module singleton) and
  how views read it without prop drilling.
- The cap/prune policy for the connection machine's per-task maps (bounded by history size? dropped
  when a task leaves the queue?).
- Whether the Retry action re-fetches only settings or the whole connection snapshot.

## Test list (proposed; to be approved at the consultation)

- [ ] A failed pause/resume shows an error message and the toggle returns to its real state
- [ ] A failed settings load offers Retry, which fetches again and shows the form on success
- [ ] Invalid threads input cannot be saved (field error, Save disabled) and never overwrites the
      stored value; a save in flight disables Save and cannot be submitted twice
- [ ] Every view that needs settings reads one shared instance: a single `GET /api/settings` per
      app load, and a save made in one view is visible in the other without a second fetch
- [ ] The connection machine's per-task maps stay bounded over a long event stream, and revisiting a
      transcript does not refetch `/turns` for a merge event seen earlier
- [ ] `pnpm dev:mock` saves settings and dismisses the device-fallback notice, matching the contract
- [ ] (must not break) The frontend suite, `pnpm build` and the e2e journey stay green

## Acceptance criteria

- No frontend action fails silently; settings has one owner with retry and validated input; MSW
  matches the API contract.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (silent pause error, dead settings retry,
  threads NaN, duplicated state, MSW drift); not yet discussed.
