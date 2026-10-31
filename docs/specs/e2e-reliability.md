# 84 — e2e reliability: a Windows journey that can gate again, no wall-clock or sleep-based assertions, and a journey that tests the current UI

Status: done (roadmap item 84, approved 2026-10-31)
Depends on: —
Blocks: [78](../../roadmap/78-stable-release.md) (a 1.0 gate: a flaky journey weakens release verification, and
1.0 claims Windows support)

## Goal

The e2e suites prove what they claim on every platform, on every run: the Windows journey is
reliable enough to rejoin `CI (all checks)`, no e2e assertion depends on a wall-clock bound or a
fixed sleep, the journey always runs against the UI the checkout builds, and the settings form
bug the macOS journey exposed is fixed rather than waited out.

## Evidence

- **The Windows journey is reporting-only**: `Windows (e2e)` runs 55 specs on one worker
  (`e2e/playwright.config.ts:13`, `workers: 1`; test timeout 180 s, expect 20 s, `:15-16`) inside a
  90-minute job (`.github/workflows/ci.yml`, `windows-e2e`). Under runner load its queue,
  transcription and order waits expire — run #400 failed 3 specs, the next run 7 different ones
  (`04-reorder`, `06-playback`, `09-merge`, `10-cancel-retry`, `11-delete`, `12-history`,
  `13-restart`), the rerun #404 passed 51/3 — and PR #79 sat 29+ minutes in `gradlew.bat e2e`. Since
  #80 (2026-10-30) the job is outside the aggregator (`docs/check-ci-gating.test.ts`,
  `REPORTING_ONLY`; `docs/specs/windows-e2e-journey.md`, amendment). The journey on Linux takes
  ~14.5 min, on macOS ~15 min; the Windows job measured 15:54 when it passed (run #389). Where the
  extra Windows time goes per spec has never been measured.
- **A wall-clock bound in the e2e unit suite**: `e2e/lib/backend.test.ts:100` asserts the failed
  start took under 5 s (`Date.now() - started < 5_000`) while the health wait itself is 60 s (`:93`);
  on a cold, loaded Windows runner it took 5136 ms with the right `exited with code 3`, and the job
  died before any Playwright spec ran (CI run 40984522606).
- **The harness never builds the UI it serves**: `e2e/lib/backend.ts:25,576,588` serve
  `frontend/dist` as found; `e2e/global-setup.ts` writes fixtures only; `:254-256` fail only when
  `index.html` is missing, not when it is stale. Item 52's edit journey clicked an older build's
  pencil and then found no editor (2026-10-23).
- **Fixed sleeps as assertions**: `e2e/tests/06-playback.spec.ts:86,92,105,109,131`
  (`waitForTimeout(500)`/`(1_500)` as "nothing happens" checks) and
  `e2e/tests/17-follow-mode.spec.ts:86,149` (500 ms hidden-checks): a regression landing after the
  window is a false pass. `e2e/tests/gifs-record.spec.ts:188,243,307,336` wait for state with sleeps
  too (its `VIEWER_PAUSE`/`STEP_PAUSE` are deliberate pacing for the recordings, not waits), under a
  900 s timeout (`e2e/playwright.gifs.config.ts:15`); it is standalone and not CI-gating.
- **The settings form drops a draft**: choosing `fixed` stores the mode only together with the count,
  so typing the count leaves an earlier field's write in flight; the sync effect keeps only the
  *focused* field's draft (`frontend/src/features/settings/SettingsView.tsx:195-196`, `keepsDraft`),
  so that write's answer (stored `auto`) sets the mode back and unmounts `#speaker-count` mid-typing.
  `e2e/tests/17-settings.spec.ts:54-56` then waits 300 s for the vanished field (macOS e2e, CI run
  40984522606); a jsdom probe reproduces it.

These findings are seven rows of `docs/known-issues.md` (the two sleep findings are one row each):
four are on `main` and leave it with this item's approval; the Windows flake, the 5 s bound and the
settings race arrive with item 74's PR and leave it once that has merged.

## Decided (consultation, 2026-10-30)

- **Scope** (the user): all six findings above, the settings race included as the app bug behind a
  journey failure.
- **Windows: measure first** (the user): record per-spec durations and traces of the Windows journey
  against Linux's, find the specs and waits that eat the time, and fix those; sharding the journey
  across jobs is decided only if the measured run still does not fit.
- **A 1.0 gate** (the user): item 78 lists this item among its gates.
- **The reliability target** (the user): `Windows (e2e)` rejoins `CI (all checks)` after **5
  consecutive green runs**, each journey step finishing within **20 min** (amended 2026-10-31: the
  13 min lower bound is dropped — a fast green run counts).
- **The UI is rebuilt when, and only when, its inputs change** (the user's digest proposal): a
  digest of every frontend build input — `frontend/src`, `index.html`, `public/`, the Vite and
  TypeScript configs, `package.json` and the lockfile, and the `docs/api/*.json` the app imports
  (`frontend/src/shared/api/fixtures.ts` and nine other modules) — is stored next to `dist`; both the
  Gradle `e2e` path and the direct Playwright path rebuild when it differs and reuse `dist` when it
  matches. Today Gradle's `frontendBuild` declares no inputs or outputs
  (`frontend/build.gradle.kts:37-42`), so `./gradlew e2e` rebuilds every run, while the direct path
  never builds. A missed input would let a stale build pass again, so the test list covers each
  input class.
- **The Windows measurement runs on CI** (the user): the agent adds a per-spec JSON reporter and
  uploads it as an artifact on the Linux and Windows journeys; the user dispatches the runs, and the
  agent reads the public artifacts.
- **The settings fix's shape is the agent's** (the user): the test list fixes the behavior; the
  workspace agent chooses the implementation, reviewed by the user.

## Proposal

1. **Measure the Windows journey**: per-spec wall time on Windows and Linux from the same commit
   (Playwright's JSON reporter, uploaded as a CI artifact), and traces of the slowest specs; record
   the numbers in this item's log.
2. **Fix what the measurement names**: the waits, setup or backend work that cost Windows its time;
   then decide sharding against the target agreed below.
3. **Return `Windows (e2e)` to the aggregator** once it meets the reliability target: remove it
   from `REPORTING_ONLY`, put it back into `ci.needs`, and amend item 79's spec again.
4. **Assert outcomes, not clocks**: the early-exit unit test checks that the start failed naming the
   exit before any health timeout could have fired, without a wall-clock bound.
5. **Rebuild a stale UI by digest**: the journey runs against a frontend built from the checkout's
   sources; a stored digest of the build inputs decides whether to rebuild (see Decided).
6. **Replace state-waiting sleeps with signals** in `06-playback`, `17-follow-mode` and
   `gifs-record` (the recorder's pacing pauses stay).
7. **Fix the settings draft race**: an earlier field's answer can no longer reset the mode or the
   count while `fixed` is being committed; `17-settings` proves it end to end (the shape is the
   agent's, see Decided).

## Open questions

None. (All settled 2026-10-30, see "Decided (consultation, 2026-10-30)".)

## Test list (approved; behaviors only)

- [x] (measure) The Windows and Linux journeys publish per-spec durations from the same commit as a
      CI artifact, and the comparison (the specs and waits that cost Windows its time) is recorded
- [x] (Windows) The Windows journey passes 5 consecutive runs, each within 20 min, and rejoins
      `CI (all checks)`; the gating check lists no reporting-only job any more
- [x] (harness) A backend that exits during startup fails the start naming its exit, not a health
      timeout, and the test holds no wall-clock bound
- [x] (harness) The journey rebuilds the UI when any build input changed — a source file, a config,
      the lockfile or a `docs/api` example the app imports — and reuses `dist` when nothing did, on
      both the Gradle and the direct Playwright path
- [x] (specs) `06-playback` and `17-follow-mode` use no fixed sleep as an assertion; a regression
      that lands after the former sleep window fails them
- [x] (specs) `gifs-record` waits for state by signals; its deliberate pacing pauses remain
- [x] (app) After choosing `fixed` and typing the count, a pending earlier write's answer leaves both
      the mode and the count as typed, and the count field stays mounted (unit test of the race)
- [x] (e2e, ADR 0046) `17-settings` passes on every platform's journey with the fix in place
- [x] (must not break) The Linux and macOS journeys stay green with the same spec count, and the
      packaged desktop suites are unchanged
- [x] (discovered) The e2e unit suite's "a start that fails stops polling the backend port" asserts
      that no further health poll arrives without a fixed sleep
- [x] (discovered) The harness's health wait still answers when a socket refuses
      `setTypeOfService` (Node 24's bundled undici throws `EINVAL` on macOS), so a backend restart
      cannot crash the Playwright worker
- [x] (review 3, 2026-10-31) A frontend test file is not a build input: editing one does not rebuild
      the journey's UI, while every real input still does
- [x] (discovered, load) A spec waiting for a transcription survives a heavily loaded runner: the
      wait follows the task's own progress (`status`, `phase`, `transcriptionProgress`, `error` from
      `GET /api/tasks`) and succeeds however long the task takes; it fails only when the task fails
      or its queue stops progressing, naming the task and the progress it reached — not a bare
      locator timeout
- [x] (discovered, packaged) Windows (packaged zip e2e) succeeds reliably: 5 consecutive green runs
      of the final commit, step under ~8 min and job under ~15 min, no hang, no retry
- [x] (discovered, packaged) A packaged-desktop spec that hangs fails fast, names the stuck step and
      still produces its evidence: step timeouts on every packaged spec step, bounded installer
      calls and teardown, the MSI log and the trace uploaded even on a timeout

## e2e stance

This item's deliverable is the e2e suites' reliability (ADR 0046); the one app change, the
settings draft race, is proven end to end by the existing `17-settings` journey spec plus a
frontend unit test of the race.

## Acceptance criteria

- `Windows (e2e)` is back in `CI (all checks)` and meets the agreed target; the Windows measurement
  is recorded.
- No e2e assertion depends on a wall-clock bound or a sleep; the journey cannot run against a stale
  UI; the settings draft race is fixed.
- The seven rows have left `docs/known-issues.md`.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-30: filed as a draft by the root agent at the user's request, from the e2e findings in
  `docs/known-issues.md` (three filed by item 74, four older). Scope, the measure-first approach to
  Windows and the 1.0 gate decided with the user.
- 2026-10-30: consultation with the user: 5 consecutive green Windows runs within 13–20 min before
  rejoining the aggregator; the UI rebuilt by a stored digest of its build inputs (the user's
  proposal); the Windows measurement on CI artifacts the user dispatches; the settings fix's shape
  left to the workspace agent. Test list approved as written; status set to spec-approved. The four
  rows on `main` left `docs/known-issues.md` in the same commit; the implementation worktree was
  created from this commit at the user's request, before the docs PR merged.
- 2026-10-30 (workspace, test-list item 1 "(measure)", artifact half; the user chose it first):
  red = `docs/check-ci-gating.test.ts` "the Linux and Windows journeys upload their per-spec
  durations from every run" failed on `the journey keeps its console log` (the config's only
  reporter was `'list'`, and neither job uploaded a report). Green: `e2e/playwright.config.ts`
  keeps `list` and adds Playwright's JSON reporter (`e2e/playwright-report/journey.json`, ignored
  and outside `test-results/`), and `e2e` and `windows-e2e` upload it with `if: always()` as
  `e2e-durations-linux` / `e2e-durations-windows` (`actions/upload-artifact@v7`, 30 days).
  Local smoke on the direct path: `01-app-loads` and `16-header-and-navigation` passed and the
  report held each spec's duration (1115 ms, 12307 ms) and the run's `stats.duration`
  (92771 ms). The difference is the global setup and backend start, which the per-spec numbers
  alone miss, so the comparison reads both. The item stays unticked until the CI runs are
  compared below.
  Refactor (the user): the workflow parser moved to `docs/ci-workflow.ts` and the check to its own
  `docs/check-e2e-durations.test.ts` (`dc8532ee`). Runs dispatched by the user on `f08756fa`:
  41004846679 (#413) and 41004939329 (#415); #414 (41004903501) was cancelled by the workflow's
  concurrency group (one queued run per ref), not by a failure.
- 2026-10-30 (workspace, test-list item 3 "(harness)", confirmed by the user): the early-exit test
  in `e2e/lib/backend.test.ts` asserted `Date.now() - started < 5_000`, which failed CI run
  40984522606 at 5136 ms with the right exit. The replacement proves "at once" by order instead:
  Node's `child_process` diagnostics channel hands the test the spawned fake backend, the health
  timeout runs on `t.mock.timers` (`setTimeout`, `Date`), which stand still until the test has
  heard the exit, and only then does the test tick past the timeout. The start must have failed
  naming `exited with code 3`, not `did not answer within`. The harness already behaved, so the new
  test passed on the current code (104 ms). Red, planted: without `child.once('exit', onExit)` in
  `waitForHealthOrExit` it failed in 112 ms on `'backend on :38851 did not answer within 60000
  ms'` — red, no hang. The plant is reverted. Throwaway check of the CI failure: with the fake
  backend's exit delayed 5.5 s, the old test failed on its bound (5590 ms) and the new one passed
  (5612 ms). The e2e unit suite is 17/17; `check-playwright`, prettier and `tsc` are green.
- 2026-10-30 (workspace, test-list item 7 "(app)", confirmed by the user): red = the new
  `SettingsView.test.tsx` "keeps the chosen fixed and the typed count when an earlier write answers
  meanwhile" failed with `Expected the element to have value: fixed / Received: auto` — the same
  state the macOS journey hit. Cause: `fixed` travels only with the count's own commit, so an
  earlier field's write answers with the stored `auto`, and the sync effect's `keepsDraft` kept
  only the *focused* field (the count), so it reset the mode and unmounted `#speaker-count`.
  Fix (`SettingsView.tsx`): the speakers branch also keeps the draft while the count field is
  focused — the mode is part of that one draft — with a comment naming the race. Green: the test
  passes (mode `fixed`, count `3`, max speakers `10` after the answer), the settings file is 25/25,
  the frontend suite 37 files / 267 tests, and prettier and `tsc --noEmit` are clean. Still to
  prove: item 8, `17-settings` on every platform's journey.
- 2026-10-30 (workspace, test-list item 1 "(measure)", comparison half; artifacts read by the agent):
  runs 41004846679 (#413) and 41004939329 (#415), both on `f08756fa`, both green — 54 passed, 1
  skipped, 0 flaky on each of the four journeys. Artifacts `e2e-durations-linux` / `-windows` read
  through nightly.link (GitHub's API needs a token). Per-spec durations, whole journey
  (`stats.duration`), `gradle e2e` step and job wall:
  | journey | report | `gradle e2e` | job |
  |---|---|---|---|
  | Linux #413 | 648 s | 703 s | 13:02 |
  | Windows #413 | 858 s | 957 s | 16:36 |
  | Linux #415 | 755 s | 811 s | 14:45 |
  | Windows #415 | 498 s | 557 s | 9:38 |
  Findings: (1) **the Windows "extra time" is not per-spec overhead.** On the same commit Windows
  swings 858 s → 498 s (1.7x) while Linux swings 648 s → 755 s (1.17x), and in #415 Windows is
  faster than Linux on nearly every spec, so the swing is runner load, not platform. (2) **Where the
  time is: the same five specs, 62–64% of every journey.** `17-follow-mode` alone is 175–203 s (~30%
  of the journey) on all four; then the transcription-wait specs `81-held-pause` (31–109 s),
  `10-cancel-retry` (24–112), `06-playback` (25–70), `40-pause-queued` (18–54) and
  `59-status-delete` (27–48). Each varies 2–4.6x between runs of the same tree. (3) **Setup is
  cheap:** inside the report setup+overhead is ≤10 s; the step above it (frontend build,
  `installDist`, fixtures) is 55–99 s. (4) The specs that failed in #400/#404 (`04-reorder`,
  `06-playback`, `09-merge`, `10-cancel-retry`, `11-delete`, `12-history`, `13-restart`) are mostly
  short ones, so their waits expired on state a loaded runner had not reached yet, not on work of
  their own. (5) **Sharding is not indicated**: 9:38–16:36 fits the 13–20 min band; the variance,
  not the mean, is what breaks it, so the lever is waits that survive load (the item's sponsor
  waits), not more jobs. Note for the reliability target: #415's 9:38 is under the band's lower
  bound — open for the user whether the lower bound still applies.
- 2026-10-30 (workspace, test-list item 4 "(harness)"; the user chose it): two reds, one list item.
  **Input set.** Red: `e2e/lib/ui-dist.test.ts` could not import the module at all
  (`Cannot find module './ui-dist.ts'`). Green: `e2e/lib/ui-dist.ts` digests `frontend/src`,
  `frontend/public`, `index.html`, `vite.config.ts`, `tsconfig.json`, `package.json`,
  `pnpm-lock.yaml` and the `docs/api/*.json` the app imports (read out of `frontend/src`), as
  root-relative posix paths and file bytes. Plant check: dropping `public/` from the input set made
  exactly `a public asset is a build input` fail. **Decision and wiring.** Red: the module had no
  `ensureUiBuilt` (`does not provide an export named 'ensureUiBuilt'`). Green: `ensureUiBuilt`
  rebuilds through the given build and records the digest only after it succeeded, and reports
  `reused` when the digest matches; both paths call it — `global-setup.ts` (direct Playwright) and
  the new `e2eFrontend` Gradle task running `e2e/ensure-ui.ts`, with `e2e` depending on it instead
  of `:frontend:frontendBuild` (dry-run graph: `pnpmInstall → e2eFrontend → e2e`, no
  `frontendBuild`). `checkPreconditions` no longer fails on a missing `dist`. **Checks:** the e2e
  unit suite is 19/19, prettier and `tsc` clean, `gradle help` configures the root build. **Smoke
  (direct path):** with a recorded digest `node ensure-ui.ts` printed `the built UI matches its
  sources` without building; a new file under `frontend/src` switched it to the rebuild branch; after
  removing the file it reused again; a real `01-app-loads` run served that dist and passed. The
  rebuild branch's actual build cannot run in this sandbox (no `/bin/sh`, so pnpm's `.bin` shim exits
  126) — that is why the probe above shows the decision, not a finished build. **Unverified:** the
  Gradle path's real execution (`gradlew e2e` → `ensure-ui.ts` → managed pnpm) needs the user's run
  or a dispatch; item 4 stays unticked until one of them reports a green journey on this branch.
- 2026-10-30 (workspace, test-list item 5 "(specs)"; chosen after the playback refactors):
  **`06-playback`.** Red, planted: `TaskTranscriptView` no longer re-synced the highlight after a
  seek — the old measured-progress assertion passed while the highlight stayed put, the new one
  failed with `following held at display 00:10 / 02:12`. Green: the highlight is asserted against the
  audio element's own `currentTime` after each seek, and each wait is an awaited state (highlight
  visible, element paused, display within 1.05 s of the element) instead of a sleep. Green twice on
  the reverted tree (34.5 s, 20.2 s). Refactors (the user approved both): `displayedSeconds(times)`
  extracted (`102784cf`); the waiting rule stated once above the test, its pause comment cut to two
  lines (`18e0f69e`).
  **`17-follow-mode`.** Red, planted: a `SCROLLED` scheduled 1.2 s after `RESUME` stood in for a
  scroll event arriving late enough to be read as a hand scroll — the new spec failed on
  `following held at <t>s`, and the old spec (`waitForTimeout(500)` then `toBeHidden()`) passed on
  the same tree (2.9 min), so the sleep window could not see it. Green after reverting the plant:
  `playheadReaches` polls the audio element's `currentTime` for progress (20 s, its message naming
  the frozen position) instead of `Date.now` plus `waitForTimeout(500)`, and resuming is checked over
  three seconds of the playhead's own progress rather than a fixed spell (3.2 min). Neither spec
  contains a `waitForTimeout` any more; prettier and `tsc -p e2e/tsconfig.json` are clean. Item 5
  ticked. Refactors (the user approved both, from the suggestions after that green): the transcript
  helpers both specs used moved to `e2e/tests/helpers/transcript.ts` (`04ca3867`); the
  follow-mode progress wait is the named `playheadAdvanced`, so `playheadReaches` decides stall vs.
  played-out in one place (`9b2fc8c7`). Both re-ran green after the change (`06-playback` +
  `17-follow-mode`: 2 passed, 3.5 min; `17-follow-mode` alone: 1 passed, 3.1 min).
- 2026-10-30 (workspace, test-list item 6 "(specs)", `gifs-record`): the evidence named four
  state-waiting sleeps (then lines 188, 243, 307, 336). **First pass** (`0646eb69`): the 4 s sleep
  that let playback run became `playingOn(page, 4, back)` — it polls the audio element's
  `currentTime` until 4 s of the clip have played with "Back to playing position" hidden, and a
  stalled playhead fails naming where it froze. The item was ticked (`6232832c`) without a log
  entry while three literal sleeps remained. **Reopened** (the user, on resuming: "signal the
  settle, keep the holds"): `reframe()`'s 500 ms settle now waits for the compact header — the
  brand mark's svg inside the home link is gone once the page has scrolled — so the frame the
  video opens on is state, not a guess; the 1 s after the "transcribed" toast is the named pacing
  `TOAST_HOLD`; the closing 1.5 s is `VIEWER_PAUSE`, and the `back`-hidden check that followed it
  is gone (the button was checked on the state the playhead reached, just before the hold, so the
  sleep no longer guards an assertion). Every remaining `waitForTimeout` in the spec is
  `VIEWER_PAUSE`, `STEP_PAUSE` or `TOAST_HOLD`. **Checks:** prettier and `tsc -p e2e/tsconfig.json`
  clean; a throwaway journey probe against the real UI saw the selector at count 1 at the top, 0
  once scrolled and 1 back at the top (1 passed, 677 ms), so the wait follows the header both ways
  and cannot pass vacuously. **Unverified:** the recorder itself — in this sandbox the run fails in
  flow 1, before the changed flow, because the devenv's `ffmpeg-audio-only-static` has no
  `libvpx` (`Unrecognized option 'crf'`); `pnpm record:gifs` on a machine with a full ffmpeg is
  the user's check.
- 2026-10-30 (workspace, test-list item 10 "(discovered)", confirmed by the user): "a start that
  fails stops polling the backend port" slept 400 ms for in-flight requests to land and 1200 ms to
  see none arrive, behind a fake backend that exited on its own 700 ms timer. Now proven by order:
  a poll counts when the harness calls `fetch` (`t.mock.method(globalThis, 'fetch')` around the real
  one), not when a request lands; the fake backend exits only on the test's cue file, written
  after the first poll; the health wait's interval and deadline run on `t.mock.timers`
  (`setTimeout`, `Date`), so once the start has failed naming `exited with code 3`, one
  `tick(60_000)` steps past the whole timeout and a `setImmediate` lets any woken wait make its
  next attempt; the `fetch` count must not move. The harness already behaved: the test passed on
  the current code (104 ms, from ~1.6 s). Red, planted: without `aborted.abort()` in
  `waitForHealthOrExit`'s `onExit`, it failed in 132 ms on `no health poll after the start failed`
  (`3 !== 2`) — red, no hang. The plant is reverted. The e2e unit suite is 19/19;
  `check-playwright`, prettier and `tsc` are green. Refactors (the user approved both, from the
  suggestions after that green): both mocked-clock start tests take their result through one
  `outcomeOf` helper (`d2204e6c`), written as an arrow at the user's follow-up (`0e886fdd`); the
  polling test's server `listen`/`close` waits use `Promise.withResolvers` (`f1a7c6f9`). The e2e
  unit suite stayed 19/19.
- 2026-10-30 (workspace, test-list item 8, Linux half only): `17-settings` passed on the direct
  Playwright path (1 passed, 34.1 s); global setup reported the built UI matching its sources, so
  the served dist carried the settings fix. Item 8 stays unticked until the macOS and Windows
  journeys run it.
- 2026-10-31 (workspace; CI run 41112050517, #421, dispatched by the user on `ff3df6b3`): green,
  every job including `CI (all checks)`. Read through the public jobs API (step timestamps) and the
  durations artifacts via nightly.link; job logs need a token (403), so no log line was read.
  | journey | report | e2e step | job |
  |---|---|---|---|
  | Linux | 763 s | 13:36 | 15:09 |
  | macOS | — (no artifact) | 11:08 | 11:46 |
  | Windows | 922 s | 17:00 | 17:40 |
  Linux and Windows: 54 passed, 1 skipped (`18-macos-metal`, macOS-only), 0 flaky, one attempt
  each — the counts of #413 and #415. The same five specs lead: `17-follow-mode` 189 / 211 s,
  `81-held-pause` 104 / 99, `10-cancel-retry` 60 / 166, `06-playback` 64 / 63, `59-status-delete`
  50 / 59. **Item 4 ticked**, on the condition its entry set (a green journey on this branch through
  Gradle): each e2e job runs `./gradlew e2e` on a fresh checkout with no UI build step before it, and
  `e2e` depends on `e2eFrontend` rather than `frontendBuild`, so the dist every journey served —
  `01-app-loads` included — came from `ensure-ui.ts`'s rebuild, on Linux, macOS and Windows (managed
  pnpm). The reuse half on the Gradle path (global setup's second `ensureUiBuilt` call finding the
  digest it was just given) is the shared function the unit tests and the local direct-path smoke
  cover; its log line was not read. **Item 8 ticked**: `17-settings` passed at the first attempt
  on Linux (9.9 s) and Windows (13.0 s); on macOS the spec has no platform gate, the config has no
  retries, and the journey step passed, so it passed there too. **Item 2**: this is Windows run 1
  of 5 within the band (step 17:00, job 17:40). **Item 9** stays open: Linux kept its count and
  the packaged desktop jobs (`.deb`, bundle, flake package, DMG, Windows zip) are green with no
  change under `e2e/desktop` or `.github/actions` on this branch, but macOS's spec count is only in
  its job log.
- 2026-10-31 (workspace; the macOS (e2e) job log of #421, handed over by the user): `> Task
  :e2eFrontend` ran Vite and printed `e2e: the UI was rebuilt from its sources` (19:07:41); global
  setup then printed `e2e: the built UI matches its sources` (19:07:49) — both halves of item 4 on
  the Gradle path, observed rather than inferred. `Running 55 tests using 1 worker` … `55 passed
  (10.2m)`: on macOS `18-macos-metal` runs (it passed, 8.0 s), so 55 is the 54 + 1 of the other
  journeys, and the branch adds or removes no spec file (`e2e/tests` only modifies three specs and
  adds `helpers/transcript.ts`). `17-settings` passed at the first attempt (9.8 s). The `Task N
  failed … ffmpeg failed to encode … Invalid data` errors in the backend log are the journey's
  deliberately broken uploads (`59-status-delete` and its neighbours passed on them). **Item 9
  ticked**: Linux and macOS green with unchanged counts, packaged desktop suites green and
  untouched.
- 2026-10-31 (the user, test-list item 2): the reliability target's 13 min lower bound is dropped
  — a green Windows run counts however fast it finished (#415's job took 9:38); only the 20 min
  upper bound stays. Decided bullet and test-list item amended. Run 1 of 5 is #421 (step 17:00);
  #422 (41159132098, on `35adcebb`, a whole-run dispatch) is running.
- 2026-10-31 (workspace; CI run 41159132098, #422): green, every job including `CI (all checks)`.
  **Windows run 2 of 5**: `gradlew.bat e2e` 9:19, job 9:44; report 497 s, 54 passed, 1 skipped
  (`18-macos-metal`), 0 flaky, one attempt each; `17-settings` 11.1 s. `17-follow-mode` 173 s is
  again the largest; the transcription-wait specs ran short on a quiet runner (`81-held-pause`
  36 s, `59-status-delete` 29 s, `06-playback` 26 s). Linux step 13:48, macOS 13:23. Under the
  amended target the 9:19 run counts.
- 2026-10-31 (workspace; CI run 41163464508, #424, on `6176401c`): green, every job including
  `CI (all checks)`; the only runs on this branch since #421 are #421, #422 and #424 (#423 ran
  elsewhere), so the streak is unbroken. **Windows run 3 of 5**: `gradlew.bat e2e` 8:40, job 9:07;
  report 459 s, 54 passed, 1 skipped (`18-macos-metal`), 0 flaky, one attempt each;
  `17-settings` 9.7 s. `17-follow-mode` 171 s, then `81-held-pause` 37 s, `06-playback` 33 s,
  `10-cancel-retry` 27 s. Linux step 12:27, macOS 12:49.
- 2026-10-31 (workspace; CI runs 41165846598 #427 and 41165893251 #428): #427 (`6176401c`) was
  cancelled before any e2e job ran — dispatching #428 (`64465a65`) 43 s later replaced it, because
  its jobs were still waiting in the concurrency group (the workspace agent had wrongly told the
  user both would run in turn). **#428**: every job green except `macOS (e2e)`. **Windows**:
  `gradlew.bat e2e` 15:12, job 15:46; report 800 s, 54 passed, 1 skipped, 0 flaky, one attempt
  each; `17-settings` 25.8 s. **macOS (e2e) red** (job log handed over by the user): 54 passed, 1
  failed — `17-settings` with `Error: setTypeOfService EINVAL` after 9.8 s, once its restarted
  backend had transcribed the clip on the CPU (04:00:44) — plus global teardown's `backend on
  :49332 still answering after 60000 ms` (`stopBackend`, `backend.ts:363`). Cause, from the
  error: Node 24.21.0 (`versions.json`, the macOS job's managed Node) bundles undici 7.29.1, whose
  `writeH1` calls `socket.setTypeOfService()` unguarded on every HTTP/1.1 request; on macOS it can
  throw `EINVAL` synchronously on a socket in teardown, outside the `fetch` promise, so no
  `try/catch` reaches it and the Playwright worker dies (nodejs/undici#5544; fixed on undici main by
  #5547, not backported to 7.x). The harness's only Node-side `fetch` is `waitForHealth`
  (`backend.ts:294`), which `17-settings` drives four times around its stop and restart of the
  backend — the socket-in-teardown case. [INFERENCE] The teardown error follows from the crash:
  the worker died inside the spec's `finally` before `saveState({ pid: fresh.pid })`, leaving the
  teardown a stale pid; not confirmed from the log. Not caused by this branch (it changes neither
  `17-settings` nor `waitForHealth`), but it is an e2e reliability failure on a gating job.
- 2026-10-31 (the user): the crash becomes a discovered item of this item's test list (the
  harness's health wait survives a socket that refuses `setTypeOfService`), done as one TDD cycle
  here; it is planned work, so it gets no `docs/known-issues.md` row. #428 counts as **Windows run 4
  of 5**: item 2 measures the Windows journey, which was green; the macOS crash is the discovered
  item. Streak: #421, #422, #424, #428.
- 2026-10-31 (workspace, the setTypeOfService discovered item, chosen by the user): red = the new
  `backend.test.ts` "the health wait answers even when every socket refuses setTypeOfService"
  (`net.Socket.prototype.setTypeOfService` mocked to throw `EINVAL`, a local server answering 200)
  failed in 29 ms with `Error: setTypeOfService EINVAL` thrown from `writeH1` in Node's bundled
  undici — the macOS crash's own stack. Green: `waitForHealth` polls through `node:http` `get`
  (`agent: false`, one connection per poll, so no pooled socket outlives the backend it reached),
  2xx at the body's `end` is an answer, and a request error, a body `error` or a `close` without
  `end` count as no answer; the new test passes (24 ms). Keeping the older tests green: "a start
  that fails stops polling the backend port" counted global `fetch` calls, which no longer happen,
  so it hung; it now counts requests on Node's `http.client.request.start` diagnostics channel, its
  assertion unchanged. Its planted red (no `aborted.abort()` on exit) still fails, on `no health
  poll after the start failed` (`3 !== 2`); the plant is reverted. The e2e unit suite is 20/20;
  `check-playwright`, prettier and `tsc` are green. **Smoke** with the real backend (direct path),
  the two specs that stop and restart it: `13-restart` 41.1 s and `17-settings` 38.2 s, 2 passed.
  The macOS proof is the next CI run; the e2e unit tests run in every journey job. Refactor: none —
  the suggested shared test-server helper rested on a miscount (two server setups in
  `backend.test.ts`, not three), and the user skipped it once corrected.
- 2026-10-31 (workspace; CI run 41169423884, #430, on `e1c0ba35`, the first run with the health fix):
  green everywhere, `CI (all checks)` included. **Windows run 5 of 5**: `gradlew.bat e2e` 11:47,
  job 12:10; report 633 s, 54 passed, 1 skipped, 0 flaky, one attempt each; `17-settings` 11.1 s.
  The five: #421 17:00, #422 9:19, #424 8:40, #428 15:12, #430 11:47 — all under the 20 min bound,
  and with #428's Windows green while macOS failed. **macOS (e2e) green with the fix** (step 25:24;
  it waited on runners, and the spec run itself was not the long part: Linux step 12:19, Windows
  11:47, macOS 25:24), so the `node:http` health wait did not crash a worker. Intermittent by
  nature, so five green Windows runs may be asked of the macOS job too before calling the crash
  gone. Next: the gating cycle (item 2's last clause).
- 2026-10-31 (workspace, test-list item 2's gating clause, confirmed by the user): red = the new
  `docs/check-ci-gating.test.ts` "the Windows journey gates merges like every other journey" failed
  on `Windows (e2e) is in CI (all checks)`. Green: the `REPORTING_ONLY` mechanism (constant, its
  loop branch and the reporting-only name assertion) is gone; `windows-e2e` is in `.github/workflows/
  ci.yml`'s `ci.needs`; the workflow's two comments and the two specs that described the job as
  reporting-only are amended (`docs/specs/windows-e2e-journey.md` gets the 2026-10-31 amendment with
  the measurement and the five runs; `docs/specs/ci-verification.md`'s cadence line). Docs checks:
  `pnpm --dir docs test`'s pieces run individually (no shell for pnpm's shims here) — tsc, prettier,
  the five checks and `node --test` 32/32, the gating file 9/9. Item 2 ticked; the test list is
  empty.
- 2026-10-31 (workspace, root review round 3, chosen by the user): red = the new
  `ui-dist.test.ts` case "a frontend test file is not a build input" (`frontend/src/features/
  App.test.tsx` added to the tree) failed with the two digests differing; green =
  `buildInputs` filters `*.test.ts(x)` out of `frontend/src`, and the case passes (2/2 in the file,
  tsc and prettier clean). Every real input class stays covered by its own case. The root review's
  other two observations, decided with the user: the three fixed waits outside this item's test list
  — `40-pause-queued.spec.ts:35` (3 s, "the worker looked again"), `52-edit-segment.spec.ts:267`
  (200 ms, no-scroll) and `59-header.spec.ts:110` (250 ms, a sampling cadence) — are **folded into
  item 85** (CI wall time), which already touches the journey, and get no row here; and the
  five-run streak's caveat (it includes #428, whose whole run was red on macOS while the Windows
  journey was green, and it spans the harness before and after the health fix; #435 is the first
  run with Windows gating) is recorded for the pull request's body.
- 2026-10-31 (workspace, the "(discovered, load)" item; reopened after run #435): the failed
  Windows job's log confirms the diagnosis. 11 failed, 43 passed, 1 skipped, 45.8 min (the usual
  journey is ~12 min). Three failures are the 240 s locator waits: `06-playback` waiting for
  `jfk-long.wav transcribed`, `10-cancel-retry` for `jfk-cancel.wav` COMPLETED, `40-pause-queued`
  for `jfk-v.wav transcribed`, each exactly `Timeout: 240000ms`. The other eight are the cascade:
  `59-dropzone`, `59-queue-options`, `59-header-typing`, `59-row-bars`, `59-row-color`,
  `59-row-controls`, `59-row-elapsed`, `59-task-row` failed on their "queue is idle" precondition
  (60 s) or a 120 s phase wait because the earlier clips were still queued or transcribing — a
  slow queue, not a wedged one: later transcription specs passed. So the waits needed to follow
  the task, not a clock. **Cycles** (one item, three reds): (1) `e2e/lib/await-task.ts` +
  `await-task.test.ts` — red `Cannot find module`, green: a task still RUNNING past 240 s resolves
  when it completes (fake clock, no real timers); (2) red: a FAILED task looped forever (the test's
  guard threw `the wait did not end on a failed task`), green: the wait rejects naming the status
  and stored error; (3) red: a queue whose progress never changed hung until the test timeout,
  green: `stallMs` (10 min) with no change across the whole task list rejects naming the clip,
  status, phase and percentage — whole-list progress so a clip queued behind real work is not
  called stalled. **Wiring**: `waitForTranscription(page, clip)` reads `GET /api/tasks` through
  `page.request`; `uploadAndOpen`, `ensureTranscript` and 20 spec waits (the 240 s
  `toBeVisible`/`toHaveCount` waits and the 300 s progress wait in `03-upload-queue`) now wait on
  the task first and keep their UI assertions at the default expect timeout; the per-spec
  `test.setTimeout(240–600 s)` ceilings are gone and the journey config's `timeout` is 20 min, a
  safety net rather than a budget. **Checks**: e2e unit suite 23/23, `check-playwright`, prettier
  and `tsc` clean; live smoke of the four rewired specs against the real backend — `03-upload-queue`
  7.0 s, `06-playback` 21.9 s, `10-cancel-retry` 28.6 s, `40-pause-queued` 19.2 s — 4 passed.
  **Unverified**: the loaded-runner behavior itself needs a CI dispatch; the stall path is proven
  by the unit test, not by a wedged backend. Remaining bounded waits outside this item's text:
  `59-row-bars`/`59-row-color` wait 120 s for the diarizing marker and `59-task-row` 240 s for the
  first RUNNING line — candidates for item 85 if the dispatch shows them.
- 2026-10-31 (the user, the packaged zip job): run #443 (`41186205834`) failed
  `Windows (packaged zip e2e)` at 09:46:29 after 53 min. `Run the MSI installer spec` started at
  09:01:34 and was still `in_progress` when the job failed; the failure upload and every post step
  were still pending, and no `desktop-test-results-windows` artifact exists — the hang left no
  trace and no MSI log. The same job takes 6–9 min in runs #421, #422, #424 and #430. Two items
  follow: no packaged spec step may hang indefinitely (step timeouts, bounded installer calls and
  teardown, evidence on a timeout), and the five-run bar for the packaged zip job is measured only
  on the final commit, with evidence.
- 2026-10-31 (workspace, packaged evidence cycle 1): red = the new
  `docs/check-packaged-steps.test.ts` failed on `desktop-e2e: "Run the packaged desktop e2e" has a
  step timeout` (and would have failed on `if: failure()` uploads and the missing MSI log). Green:
  every packaged spec step in the five packaged jobs has `timeout-minutes` (`desktop-e2e` 20,
  flake 60, bundle 90, macOS DMG 20, both zip spec steps 10, MSI install 15, MSI spec 15); every
  packaged job's trace upload runs `if: always()` and the composite action tolerates an empty
  `test-results/` (`if-no-files-found: ignore`); the MSI install logs with `/l*v` to
  `%RUNNER_TEMP%\msi-install.log`, uploaded as `msi-install-log-windows` with `if: always()`.
  Plant check: removing `timeout-minutes` from `Run the MSI installer spec` fails the check on that
  step; restored. The docs suite is 33/33, tsc and prettier clean. Not run here: the jobs
  themselves; a timeout's upload path is proven by configuration, not by a timed-out job.
- 2026-10-31 (workspace, packaged evidence cycle 2, bounded teardown): red = the new
  `e2e/lib/bounded.test.ts` "a close that never resolves is killed at its deadline and reported"
  could not import `./bounded.ts`. Green: `closeWithin(close, { deadlineMs, what, kill })` races
  the close against a deadline on the (mocked) clock, kills once when the deadline wins and warns
  `<what> did not close within <ms> ms; killed it`, returning `closed` or `killed`; a close that
  rejects in time still rejects, and a late rejection is swallowed rather than left unhandled.
  `execFileWithin(file, args, { ceilingMs = 180_000 })` runs a child asynchronously through the
  same deadline, resolves with its exit code and output for any exit, and rejects naming the
  process when it had to be killed. The e2e unit suite is 24/24; prettier and `tsc` clean. The
  specs are not wired yet (next step).
- 2026-10-31 (workspace, packaged evidence wiring): the packaged specs use the bounded helpers.
  `desktop-tests/helpers/model-server.ts`: `close()` releases held responses and calls
  `server.closeAllConnections()` before `server.close()`. `desktop-tests/helpers/app.ts`: the
  trace-saving `app.close` saves the trace within 60 s and closes the app within another 60 s,
  each through `closeWithin`, killing the app's process tree (`taskkill /T /F` on Windows, where
  the backend JVM is a child; `SIGKILL` elsewhere) and naming it when a deadline wins; the trace is
  attached only when it was saved. `15-msi-install.spec.ts`: every `msiexec`, and both PowerShell
  probes, run through `execFileWithin` (3-minute ceiling, async — `spawnSync`/`execFileSync`
  blocked the worker, so Playwright's timeout could not end the spec); each `msiexec` logs with
  `/l*v` into the test's output directory, which the always-on trace upload keeps; `waitFor`
  takes async probes. **Smoke** (throwaway desktop-config spec, the model server with a held
  response open): the new `close()` resolved in 1 ms; with the previous `model-server.ts` restored
  the same close never settled and `closeWithin` killed it at its 10 s deadline, reporting
  `the model server did not close within 10000 ms; killed it`. The e2e unit suite is 24/24,
  prettier and `tsc` clean. **Not run here**: the Windows packaged specs themselves (no Windows,
  no MSI in this sandbox) — the CI dispatch is their evidence. Both packaged test-list items stay
  unticked: the five-run bar needs runs of the final commit, and the hang item needs a run that
  shows the bounded steps and evidence on Windows.
- 2026-10-31 (workspace; CI run 41191596213, #444, `workflow_dispatch` on `test/84-packaged`):
  commit `527bada5`, one commit on `17a2635e` with tree `41f35c5a…` — the workspace head
  `4783b652`'s tree, pushed from the user's laptop as a test-only ref (ADR 0050: the 45 cycle
  commits are never pushed). Green everywhere, `CI (all checks)` included. **Windows (packaged
  zip e2e)**: job 6:27; spec steps 0:43 (unpacked zip), 0:55 (MSI install + installed-app specs),
  0:26 (MSI spec) — 2:04 of specs against the ~8 min bar, the job against ~15 min; no retry. The
  always-on steps ran on the green run: `msi-install-log-windows` (77 KB) holds the verbose
  install log ending `Installation completed successfully … Installation success or error status:
  0` and `MainEngineThread is returning 0`; the trace upload ran and, with no failed spec, created
  no artifact. Other packaged jobs, each green under its new step timeout: deb 7:01 (specs 3:09),
  flake 5:52 (build + specs 4:47), bundle 11:50 (9:15), macOS DMG 4:10 (specs 1:13). **Windows
  (e2e)** with the progress-based waits: `gradlew.bat e2e` 15:12, 54 passed, 1 skipped, 0 flaky;
  `06-playback` 52 s, `10-cancel-retry` 71 s, `40-pause-queued` 67 s, `17-follow-mode` 191 s.
  Linux e2e 12:51, macOS 14:37. **Counting**: this is packaged run 1 toward the five, for the code
  in tree `41f35c5a`; this log entry itself changes the tree, so the counting rule (code unchanged,
  only this item's log differs) is the user's to confirm. The hang item stays unticked: a green run
  cannot show a timeout's evidence.
- 2026-10-31 (the user): the counting rule is confirmed — a run counts toward the packaged five
  when the code is unchanged and only this item's log differs. #444 is packaged run 1 of 5.
- 2026-10-31 (workspace; CI run 41204858708, #445, `workflow_dispatch` on `test/84-hang`): the
  deliberate-hang proof. Commit `7aebe929`, rebuilt on the user's laptop from the plant, one commit
  on `17a2635e` with tree `7c1a66ba…` — the workspace tree `0263d026` plus a synchronous
  `execFileSync('powershell.exe', … 'Start-Sleep -Seconds 3600')` right after the spec's MSI
  install, a block the Playwright worker cannot time out, as in #443. Result, Windows (packaged zip
  e2e) red as intended: `Run the MSI installer spec` ran 13:29:41 → 13:44:42 and the log reads
  `##[error]The action 'Run the MSI installer spec' has timed out after 15 minutes.`; the job ended
  at 13:44:58 (23:55), not after 53 min. Both always-on uploads ran: `msi-install-log-windows`
  (the workflow's `msiexec /i` log, `Installation success or error status: 0`) and
  `desktop-test-results-windows` with the spec's output directory — `msiexec-install.log`
  (status 0), `msiexec-previous-uninstall.log`, `error-context.md` naming the planted command, and
  Playwright's `trace.zip`. Killing the step also killed the sleeping child, so the spec itself
  reported `Command failed: powershell.exe … Start-Sleep -Seconds 3600` at
  `15-msi-install.spec.ts:50` before the upload. Every other job green; `CI (all checks)` red
  only for the planted job. No app trace exists here: the hang comes before the app launches, and
  the 60 s trace and close deadlines for a hang inside the app's session are proven by the
  `closeWithin` unit test and the model-server smoke. The ref is throwaway (to be deleted); the
  planted tree does not count toward the packaged five. **Hang item ticked.**
- 2026-10-31 (workspace; the packaged five, runs #444, #446, #447, #448, #449): five consecutive
  green `Windows (packaged zip e2e)` runs on commit `dc06ca98`, tree `9e9e16cf…` (the code of
  #444's tree `41f35c5a` plus the item's log entries; the counting rule the user confirmed). Each
  was a `workflow_dispatch` of `ci.yml` on `test/84-packaged`, one at a time, no retry.
  | run | job | unpacked zip | MSI install + app specs | MSI spec | specs total |
  |---|---|---|---|---|---|
  | #444 | 6:27 | 0:43 | 0:55 | 0:26 | 2:04 |
  | #446 | 9:00 | 0:56 | 1:13 | 0:38 | 2:47 |
  | #447 | 9:44 | 1:00 | 1:19 | 0:53 | 3:12 |
  | #448 | 6:39 | 0:41 | 0:49 | 0:29 | 1:59 |
  | #449 | 9:27 | 0:57 | 1:35 | 0:45 | 3:17 |
  Every job is inside the bar (specs under ~8 min, job under ~15 min); the slowest step was
  `Install the MSI and run the packaged specs against the installed app` at 1:35. On each run
  `Upload the MSI install log` ran and produced `msi-install-log-windows` (77–78 KB), and the trace
  upload ran and created no artifact (no failed spec). No hang, no near-timeout. #446, #447 and
  #449's whole runs, `CI (all checks)` included, were green; the packaged job of #449 was green
  before the run's aggregator ran, and its `Windows (e2e)` was still in progress at the time of
  this entry. **Packaged item ticked.** The five runs were against `9e9e16cf…`; this entry and the
  tick change only this file.
- 2026-10-31 (workspace): the test list is empty again and both packaged items are ticked;
  `Status: in-review`. The item's diff is ready for the root agent to review, squash and
  re-prepare the pull request. The workspace branch's test-only refs (`test/84-packaged`,
  `test/84-hang`) are the user's to delete; the planted commit is never part of the branch.
- 2026-10-31 (root agent, PR preparation): approved after review, with one correction to the
  record. The two five-run sets above were measured on trees **before roadmap item 73 merged**:
  #444's `527bada5` and #446–#449's `dc06ca98` (tree `9e9e16cf`) carry item 84's code on the
  previous `main` (`17a2635e`) and none of item 73's reassignment (no
  `docs/specs/speaker-reassignment.md`, the pre-73 `SpeakersPanel`, `SegmentRepository` and
  `connectionMachine` blobs, no `09-reassign` spec). Item 73 then merged as `2d07bb67` while this
  item was in review, and the branch was rebased onto it, so the final tree adds item 73's
  backend, UI, event wiring and a new journey spec (`09-reassign`, which carries `expect.poll`
  defaults and a 240 s test ceiling). The item's five-green bar is therefore counted again on the
  pull request's head before merging — five consecutive `workflow_dispatch` runs of `ci.yml`,
  one at a time, no retry, `Windows (e2e)`, `Windows (packaged zip e2e)` and `CI (all checks)`
  green each time. The item's spec and test list moved from
  `roadmap/84-e2e-reliability.md` to `docs/specs/e2e-reliability.md` with `Status: done`, rebased
  onto `main` (`2d07bb67`) and squashed to one commit (ADR 0050). Meanwhile the title, spec and
  test list are unchanged; a local Linux journey on the final tree passed 55 specs (1 skipped,
  8.2 m), the first run of the combination with item 73.
