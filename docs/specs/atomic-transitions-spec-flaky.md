# 47 — ProcessingEngineAtomicTransitionsSpec is schedule-dependent (flaky)

Status: done (roadmap item 47; pending merge as PR #7)
Depends on: [11 — Processing engine](processing-engine.md) (done)
Blocks: —

## Goal

Make the tests that fail CI by scheduling luck deterministic. First `ProcessingEngineAtomicTransitionsSpec`'s
"a running task whose accepted pause's write commits before its completion's" test, so the item-21/40
command-race coverage never fails a `gradle check` by scheduling luck. Widened on 2026-10-15 (see the
Approval log): the e2e journeys whose assertions depend on how fast the app runs, starting with
`03-upload-queue`'s liveness probe, which turned PR #7's `E2E (Playwright)` leg red.

## Evidence

- Reported 2026-10-13 by item 33's workspace rebase gate (working on plain `main`, not item 33's
  code): the test failed with `org.opentest4j.AssertionFailedError: expected:<[1L, 2L]> but was:<[1L]>`
  at `ProcessingEngineAtomicTransitionsSpec.kt:302`, reproduced on a detached `main` worktree.
- Main-tree agent stress runs (same day): failed 1 of 10 isolated runs; then 25 consecutive passes.
  So the flake is real but sparse.
- The failing assertion is the new item-40 regression pin's two-event receive:
  `val completedIds = listOf(fixture.completed.receive(), fixture.completed.receive()); completedIds.toSet() shouldBe setOf(first, next)`.
  The actual `{1}` means both receives returned task A's id — the worker published A's COMPLETED
  twice, or B's COMPLETED never arrived and something else re-sent A's. The race under test drives
  the writer lock with three writers (the worker's own chunk-status write, the pause's conditional
  write, the test's manual `setCompleted`), and the manual `setCompleted` does not publish; the
  assertion assumes exactly two COMPLETED events in a specific scheduling of the worker's flow.
- The test was added as a "green on arrival" regression pin for item 40's second review (the pause
  that wins its write and then loses to the completion must not hold the next run's gate). It pins a
  real invariant, but its event-count assertion is a second, schedule-dependent claim.
- 2026-10-15, PR #7 (run 38661372993, `E2E (Playwright)`): `e2e/tests/03-upload-queue.spec.ts:49` failed
  `expect(received).toBeGreaterThan(expected)` — `Expected: > 1, Received: 1`, "Timeout 60000ms exceeded
  while waiting on the predicate". The probe (added by `656fcd04`, PR #4, whose sandbox could not run the
  e2e journey) sampled the `jfk-a.wav` row's transcription `aria-valuenow` only after five sequential UI
  awaits; the CI log shows all three queue clips finished within ~4 s of the test's start
  (`SpeakerSummary [task 1..3]` at 00:43:58, 00:44:00, 00:44:01) while the poll ran until 00:44:57 — the
  bar's live window was over before sampling began.

## Decided (consultation, 2026-10-13)

- **Keep the invariant assertions**: both tasks end COMPLETED, no dead started-PAUSED row, `diarizer.passedGate` true.
- **Replace the two-event receive** — an exact-count scheduling claim (A's COMPLETED can be published twice, the manual `setCompleted` does not publish) — with: wait on repository state until both tasks are COMPLETED, then drain the event channel with a bounded timeout and assert both task ids appear at least once. Duplicates and extra events tolerated; ordering not asserted.
- **Reproduce first** (loop the spec until it trips), then fix, then run the spec 50× isolated plus the full suite.

## Decided (2026-10-15, the user)

- **The e2e flakes are this item's work**: "The e2e tests are flaky. The purpose of this roadmap item is to
  make them not be flaky." Fixed in this branch, on top of the squashed `2005fdff`; squashed again after
  approval.
- **Window-free assertions**: an e2e assertion about a live value must not depend on when the test looks —
  record in the page from before the window opens, snapshot a live state before a wait that can outlast
  it, and treat a legal race outcome (a completion winning over a command) as an outcome, not a failure.

## Test list (approved 2026-10-13 with the spec; discovered items appended 2026-10-15)

- [x] The spec passes 50 consecutive isolated runs (the flake's observed frequency is ≤ 1/10, so 50
      clean runs bound the residual probability)
- [x] The invariant still holds when the test is re-run: A ends COMPLETED, no dead started PAUSED
      row, B diarizes and completes, and the drained events contain both ids (the state/gate
      assertions that made the test a pin are unchanged; the exact event count is not asserted)
- [x] (must not break) `gradle check` and `gradle integrationTest` stay green
- [x] (discovered 2026-10-15) `03-upload-queue`'s liveness probe records the transcription bar's values in
      the page from before the uploads, so it cannot miss the bar's live window; a bar stuck at 0 or at
      100 still fails
- [x] (discovered 2026-10-15) `17-audio-while-transcribing` checks the item-37 condition (the background
      task RUNNING) before the player's up-to-60 s poll, which the background clip can outlast
- [x] (discovered 2026-10-15) `40-atomic-transitions` tolerates a completion winning at every
      pause/resume/cancel step instead of failing on a button whose row has left the queue
- [x] (discovered 2026-10-15) `17-audio-while-transcribing`'s cleanup cancels the background task only
      while its row is there, tolerating a completion during the player's poll, and asserts the task ends
      in a legal terminal state — COMPLETED or CANCELED, never FAILED and never stuck in the queue
- [x] (discovered 2026-10-15) `40-atomic-transitions`' cancel confirmation is guarded like its initial
      click, so a completion landing between the two clicks is an outcome, not a failure
- [x] (must not break) the two corrected journeys and the full e2e suite stay green in a local run of the
      corrected head
- [x] (must not break) the full e2e suite stays green in a local run
- [x] (must not break) the full e2e suite stays green in PR #7's `E2E (Playwright)` job (the user's run)

## Acceptance criteria

- The command-race spec never fails by scheduling luck: the added pin is deterministic.
- The e2e journeys' assertions do not depend on how fast the app runs: a live value is recorded or
  snapshotted, never sampled after a wait that can outlast it.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed by the main-tree agent after the item-33 rebase gate hit it on plain `main` and
  stress runs reproduced the flake (1/10, then 25 clean). Kept out of item 33's merge per the user.
- 2026-10-13: consultation — keep the state/gate invariants; replace the exact-count two-event
  receive with a bounded drain asserting both ids appear (duplicates tolerated); reproduce first,
  then 50× isolated runs plus the full suite. Test list approved; status `spec-approved`.
- 2026-10-14: kickoff — worktree `workspaces/47-atomic-transitions-spec-flaky`, branch
  `roadmap/47-atomic-transitions-spec-flaky`, cut from the main tree's `main`. Cause of the
  remaining `main` red (run #38650675971): this spec is the one untagged suite member that still
  fails by scheduling luck, and it failed inside `nix build`'s `gradle check` while the same
  commit's CI fast gate passed — the 1/10 flake item 47 was filed for.

- 2026-10-14 (cycle 1, test-list item 2, the pin's event assertion): red — the spec's first isolated run in
  the worktree failed exactly as filed, `expected:<[1L, 2L]> but was:<[1L]>` at
  `ProcessingEngineAtomicTransitionsSpec.kt:302`. Cause (inferred from the code): the accepted pause's
  `publish` reads the task row back over a read-only connection, so the test's manual `setCompleted` (which
  does not publish) can commit COMPLETED before that read — the pause publishes A's COMPLETED and the
  worker's terminal `publish` (`ProcessingEngine.kt:508-510`) publishes it again; B's event arrives later,
  so the two receives saw `[A, A]`. Green: the exact two-event receive is replaced by a bounded
  `pumpUntil`/`drain` loop that asserts both ids appear at least once (duplicates and order tolerated);
  the A-COMPLETED, no-dead-started-PAUSED-row, `passedGate` and `next`-COMPLETED assertions are unchanged.
  `gradle test --tests stt.engine.ProcessingEngineAtomicTransitionsSpec` green (6 tests, 10 s warm).

- 2026-10-14 (cycle 2, test-list item 1): 50 consecutive isolated runs of `gradle test --tests
  stt.engine.ProcessingEngineAtomicTransitionsSpec --rerun` (`--rerun` so each run really executes; warm
  daemon) — 0 failures, 293 s total, ~5.9 s per run. The pre-fix code failed its first isolated run here
  (1 red in 1 run, the filed assertion), so the 50 clean runs are a determinism bound on the fix, not a
  measurement of the old rate. Item ticked.

- 2026-10-14 (cycle 3, test-list item 3, the must-not-break gate): `gradle check` green — `ktlintCheck`
  clean, 291 unit tests, 0 failures (58 s); the atomic-transitions spec runs there (it is untagged).
  `gradle integrationTest` was red, red, green: the first two runs failed `AudioJoinIntegrationSpec` with
  `expected:<200 OK> but was:<500 Internal Server Error>` at `AudioJoinIntegrationSpec.kt:290` (337 tests,
  1 failed, 274 skipped); the third was green (337 tests, 0 failures, 274 skipped, 66 s). The reds are
  attributed to the pre-existing flake in `docs/known-issues.md:62` (LOW: passes alone and on a rerun,
  suspected ffmpeg/parallel contention, recheck after item 44). The attribution is structural, not just
  historical: item 47 changes only `ProcessingEngineAtomicTransitionsSpec`, an untagged spec that
  `gradle integrationTest` (the `Integration` tag's filter) never executes, so the change can only reach
  that gate by breaking the shared test compilation — it compiled. The same flake, same shape (first run
  red, later runs green), is recorded for `main` in item 40's spec log, and item 40 merged on that basis.
  Item 3 ticked: both gates green on the record, with the two reds and the attribution documented here.

- 2026-10-15: PR #7 (the squashed `2005fdff`) went red on `E2E (Playwright)` (run 38661372993) at
  `03-upload-queue.spec.ts:49`, a different flake from the one this item was filed for. The user widened
  the item to the e2e flakes and chose to fix them in this branch (Decided, 2026-10-15).

- 2026-10-15 (local e2e harness): the sandbox has no `/bin/sh`, but the suite still runs — the e2e
  launcher spawns `E2E_BACKEND_CMD` without a shell (`java -cp backend/build/install/stt-backend/lib/*
  stt.MainKt` after `gradle installDist`), the frontend is built with `node node_modules/vite/bin/vite.js
  build` (asset hashes identical to CI's), `STT_STATIC_DIR` points at it, and the devenv's
  `PLAYWRIGHT_BROWSERS_PATH` provides Chromium.

- 2026-10-15 (cycle 4, discovered, `03-upload-queue`): the red is CI's (run 38661372993): the probe sampled
  after five UI awaits, so with ~1 s clips it could find a single value; locally the pre-fix probe passed
  6/6 — the window is lost on CI's timing, not here. Green: `recordTranscriptionPercentages` installs a
  `MutationObserver` in the page before the uploads and records every `aria-valuenow` of the `jfk-a.wav`
  row's transcription bar; the poll reads the recorded set's size. A throwaway diagnostic run printed the
  recorded values, `0, 100` (one-chunk clip: first paint at 0, then 100). Fixed spec green 6/6 locally.

- 2026-10-15 (cycle 5, discovered, `17-audio-while-transcribing`): the item-37 condition (background task
  RUNNING) was checked after the player's up-to-60 s `readyState` poll, so a background clip finishing
  inside the poll would fail the test though nothing regressed. The snapshot now precedes opening the
  player. Not reproduced red (the clip outlasted the poll in every observed run); green locally 1/1.

- 2026-10-15 (cycle 6, discovered, `40-atomic-transitions`): `count() > 0` then `click()`, and the Resume
  wait after an accepted pause, were not atomic against the task completing — completion legally wins
  over a pause, item 40's own race — so a lost race failed on a vanished button. Every step now runs
  through `unlessFinished`, which accepts a failed step only when the row has left the queue and stays
  loud otherwise. Not reproduced red; green locally 3/3.

- 2026-10-15 (audit of the other specs, read-only, every hit judged in context): sound (signal-based
  waits) — 01, 02, 04, 05, 07-14, 16 and `17-settings`; not flaky — `03-upload-queue`'s
  `not.toBeVisible()` notice checks (an absent notice passes at once; only out-of-order notices fail them)
  and `40-pause-queued`'s 3 s sleep (slow, but the invariants after it hold forever). The remaining four —
  `15-screenshots`, `06-playback`, `17-follow-mode`, `gifs-record` — are not fixed in this branch: the user
  chose the backlog for them, and they are rows in `docs/known-issues.md` § Tests with their evidence and
  fix directions.

- 2026-10-15 (must not break, local): the full e2e suite — 21 tests, the three changed journeys included —
  green in one local run (21 passed, 5.7 min; `prettier --check .` clean in `e2e/`). PR #7's
  `E2E (Playwright)` job is the remaining gate (the user's run).

- 2026-10-15: the user confirmed the three discovered test-list items and chose the backlog for the four
  remaining timing-dependent journeys (see the audit entry above), so this branch carries only the observed
  red and the two neighbouring windows.

- 2026-10-15 (cycle 7, discovered, `17-audio-while-transcribing`; found by the item's second review): the
  cleanup clicked Cancel and confirmed it unconditionally. If the background clip finishes during the
  player's `readyState` poll, its queue row and dialog are gone before cleanup, so the clicks auto-wait
  into a locator timeout. Red (controlled reproduction, not a timing-in-poll reproduction): the natural
  pre-fix run passed (26.0 s total; test 6.5 s). A diagnostic then waited for background status `COMPLETED`
  before cleanup; the unguarded Cancel click failed at line 74 after the 360,000 ms test timeout, waiting
  for `Cancel jfk-r.wav`. Green: with the guard restored and the same forced-completion condition, the
  journey passed (14.7 s test time). The cancel/confirmation pair now runs in `unlessGone`, which accepts
  a failed step only after the row has left the queue, and the History card must read COMPLETED or CANCELED;
  FAILED and a task stuck in the queue stay loud.

- 2026-10-15 (cycle 8, discovered, `40-atomic-transitions`; found by the item's second review): the initial
  Cancel opens a portaled dialog but does not remove the RUNNING task's pending row. When the task reaches a
  terminal state, QueueView removes the row and the dialog unmounts with its TaskRow, so completion can win
  before either the initial Cancel click or the confirmation. An instrumented throwaway run showed the dialog
  disappearing on completion (`dialog visible: false`, `status: COMPLETED`) and the unguarded confirmation
  click timing out. Green: both clicks run through `unlessGone`, which accepts a failed step only after the row
  has left the queue; the remaining assertions are untouched (row gone, the queue pause untouched, History
  COMPLETED-or-CANCELED, the follow-up upload completes). A throwaway diagnostic also observed the cycle-7
  mechanism: when a trigger has vanished, the click throws and the guard's post-condition holds. Local: the
  journey 3 passed (12.2/13.2/12.9 s), `prettier --check .` clean in `e2e/`.

- 2026-10-15 (refactor, approved after cycle 8): the race guard and the terminal-badge assertion moved to
  `e2e/tests/helpers/ui.ts` — `unlessGone(row, step)` (returning whether the step ran, so the loops keep
  their shape) and `expectCompletedOrCanceled(page, filename, timeout = 120_000)` — and both journeys import
  them instead of carrying local copies. Behavior is preserved except one bound: `40-atomic-transitions`'
  History badge wait moves from the config default (20 s) to the helper's 120 s, which only lengthens a
  failure. Verified: each corrected journey 1 passed (13.1 s / 13.3 s) and the full suite 21 passed
  (5.8 min), `prettier --check .` clean in `e2e/`.

- 2026-10-15 (must not break, corrected head): the two corrected journeys and the full e2e suite are green on
  the head that carries cycles 7 and 8 plus the refactor — 1 passed each (13.1 s / 13.3 s) and 21 passed in
  5.8 min, `prettier --check .` clean. The PR #7 `E2E (Playwright)` item stays unticked until the user's run
  of this head.

- 2026-10-15 (PR #7's E2E job, the corrected head): CI run #23 (`38791480479`, event pull_request on
  `roadmap/47-atomic-transitions-spec-flaky` @ `720f83f`) — all eight jobs success: E2E (Playwright) 9:10,
  Backend (gradle check 1:48, gradle integrationTest 3:00), Frontend (1:00), Nix (flake check 1:00, devenv
  shell 1:42, build 0:39) and CI (all checks). The last test-list item is ticked: the e2e suite is green on
  the exact head carrying cycles 7 and 8 plus the refactor, and the item's own `gradle check`/`nix build`
  legs are green with it. Test list complete; the squash (ADR 0050) and the merge follow the user's approval
  of the diff.