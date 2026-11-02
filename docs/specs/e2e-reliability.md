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
- [x] (discovered, load) No journey spec inherits another spec's pending work: before each spec every
      QUEUED, RUNNING and PAUSED task is canceled, a paused queue is resumed, and the queue is idle,
      so the spec's own task starts at once; when the backend does not answer (a restart spec left it
      down), the reset logs and steps aside instead of failing the spec
- [x] (discovered, load) A backend that answers, but not with the task list and queue state the reset
      reads, fails the reset with the request and what came back, instead of passing for a backend
      that does not answer
- [x] (discovered, load) A wait on a task's processing state follows the task, not a fixed ceiling:
      59-status-delete (FAILED) and 81-held-pause (Transcribing) first, then the other processing-
      state waits and per-spec test.setTimeout values below the 20-minute net, list agreed with the
      user at that checkpoint
- [x] (discovered, load) A journey run ends at the first failing spec: red in minutes with the failed
      spec's trace uploaded, instead of the rest of the suite running under the 20-minute net
- [x] (discovered, load) A wait on a browser-side promise is bounded: an event that never arrives
      fails the spec naming what was awaited, in seconds, instead of running to the 20-minute net
- [x] (discovered, load) A wait that replaces a terminal run ignores it: after Retry, the wait for the
      retried run's completion does not end on the old CANCELED row
- [x] (discovered, load) An observation of transcription progress follows the task: the percent
      advancing ends it, and the stall window — not a 120 s clock — ends it when it does not
- [x] (discovered, load) A queue event that arrives out of order does not hide a pending task: the
      Queue page keeps the row of a task the server still lists as pending — the server builds a
      queue snapshot before it publishes it, so two uploads' snapshots can cross — and the engine
      publishes each snapshot in the order it read it, so a reorder always sends the server's full
      pending set and the page never shows a row the server does not have
- [x] (discovered, load) A progress observation tolerates the gap between two progress
      events on a slow runner: it uses the shared 10-minute no-change window instead of a 120 s
      one, compares the row's percentage and the API's fraction in the same unit, and observes a
      ~77 s clip rather than a ~176 s one — so one chunk's compute at 0 % is not mistaken for a
      stall, and the spec costs a slow runner minutes less
- [x] (discovered, load) A spec that kills the backend waits for its upload to be accepted: 17-settings
      waits for the clip's own QUEUED state before stopping the backend, so a slow upload is not
      aborted mid-flight and then waited on for the whole stall window (run #465)
- [x] (discovered, load) A batch option the user picks before the stored settings arrive survives them:
      the queue's upload options are prefilled only while untouched, so a language chosen during
      the settings load is the one the upload carries (run #471's task stored `en` after the user
      had chosen German)
- [x] (discovered, load) A green run is not failed by its own teardown: the harness's temporary data
      and fixtures directories are removed with retries, and a directory the OS still holds is
      reported instead of thrown — in the global teardown and in 60-device-probe (run #475: 55
      passed, 1 skipped, EPERM)
- [x] (discovered, load) No spec proceeds on an answer it has not read: every upload has its own
      answer counted before the helper returns, a reorder has its PUT's 200 before a reload or a
      backend stop, a restart has every stored settings field before the stop, and retries, SSE
      captures and captured contract bodies are awaited before the assertions that need them
- [x] (discovered, load) A spec that reads a shared transcript derives its task from the task list
      (and uploads the clip when it is absent), and 59-transcript-actions puts a shared clip's
      speaker name back
- [x] (discovered, load) A spec that observes a running task reads a page-side record: 59-task-row
      and 59-favicon sample the row and the favicon on every DOM change instead of polling for a
      phase or an href that can be gone between two polls, and 59-row-bars/59-row-color no longer
      read the running row's text (run #481: "completed before reaching the state the wait wanted"
      on a fast Linux runner)
- [x] (discovered, load) Windows (e2e) and Windows (packaged zip e2e) pass 5 consecutive
      whole-run CI runs of the final head (`pull_request` or `workflow_dispatch`), one at a time, no
      retry, `CI (all checks)` green

## e2e stance

This item's deliverable is the e2e suites' reliability (ADR 0046); the one app change, the
settings draft race, is proven end to end by the existing `17-settings` journey spec plus a
frontend unit test of the race.

## Acceptance criteria

- `Windows (e2e)` is back in `CI (all checks)` and meets the agreed target; the Windows measurement
  is recorded. The target is a green run inside the job's 90-minute budget (`ci.yml`): a normal
  runner lands in 12–14 min and the 13–20 min band is the observation the item measures against,
  not a second gate — run #464 (2026-11-02) was 43 of 56 specs green after 57 min on a runner whose
  whisper-cli was ~10× slower than #462's, at the same speed as #462 on every DOM-only spec, because
  every wait follows task state rather than a clock. Wall time itself is
  [item 85](../../roadmap/85-ci-wall-time.md)'s subject.
- No e2e assertion whose outcome depends on how long work takes: every wait for the app to reach a
  state ends on that state, and a stall window names the task when the queue stops changing. Three
  `page.waitForTimeout` calls remain as named exceptions, each proving that something did *not*
  happen (`40-pause-queued.spec.ts:37`, `52-edit-segment.spec.ts:261`) or sampling a playing position
  (`59-header.spec.ts:109`), where no state signal exists to wait on. The journey cannot run against
  a stale UI; the settings draft race is fixed.
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
- 2026-11-01 (reopened, the user; the root agent's prepared PR head): CI run #452 (`41241404336`,
  head `05bebd89`, `workflow_dispatch`) is red on `Windows (e2e)` after a 45.1 min `gradlew.bat e2e`
  step; #453 and #454 on the same commit were green (12.0 and 9.6 min; Linux 11.7 / 8.8 / 9.6).
  Two specs failed at their 120 s waits. `59-status-delete.spec.ts:17` (the FAILED badge): the
  History snapshot at failure stops at task 41, so the broken clip was still queued behind clips
  earlier specs left running — `59-row-bars` jfk-af (33 s), `59-row-color` jfk-ah (55 s) and
  `59-row-elapsed` jfk-ag (143 s), which asserts for 2 s and leaves it running; Linux passed the
  same spec in 46 s. `81-held-pause.spec.ts:19` (Transcribing): the queue snapshot shows
  `59-task-row`'s jfk-aa (176 s) "Transcribing… 55% · 03:48" ahead of jfk-aj/jfk-ak QUEUED.
  `59-task-row`'s paused jfk-ab is not the blocker: `ProcessingEngine.nextTask` skips a
  never-started PAUSED task (lines 477–489). `59-task-row`'s own idle-queue wait passed at 107 s
  of its 120 s. So specs inherit each other's pending work, and the processing-state waits still
  carry fixed ceilings. The earlier five-run sets (#421–#430 journey; #444, #446–#449 packaged)
  ran on trees before item 73 merged (`2d07bb67`); the streak restarts on the new head. Three
  discovered items appended (wording to be confirmed with the user at the first checkpoint);
  `Status: in-progress`.
- 2026-11-01 (the user, first checkpoint): the three discovered items are approved, the first with
  two amendments — the reset also resumes a paused queue, and an unanswering backend makes it log
  and step aside so the spec reports its own failure. A read-only scout of the journey found no spec
  that depends on a predecessor's pending task or inherited pause; the terminal tasks later specs
  reuse (jfk-a/jfk-b from `03`, jfk-n from `16`) are awaited to COMPLETED by their producers; `04`,
  `41`, `59-queue-options`, `59-row-elapsed` and `59-task-row` leave tasks pending
  (`59-status-delete` transiently), and six `59-*` specs poll for an idle queue at start (120 s).
  First test chosen: the reset loop's unit test on a fake clock.
- 2026-11-01 (workspace, queue-reset cycle 1): red = the new `e2e/lib/reset-queue.test.ts` "the
  reset cancels every pending task once, resumes a paused queue, and ends when nothing is pending"
  could not import `./reset-queue.ts`. Green: `resetQueue(api, options)` reads the task list and
  queue state through a small `QueueApi` (`snapshot`, `cancel`, `resume`), resumes a paused queue
  once, cancels each QUEUED, RUNNING or PAUSED task exactly once, and returns once nothing is pending
  and the queue is not paused. The test runs on a fake clock (the `now`/`sleep` seams, no real
  timers): a RUNNING task stays RUNNING for three polls after its cancel, the way the backend awaits
  the process, and the reset waits for it; a COMPLETED task is never touched. The e2e unit suite is
  25/25, prettier and `tsc` clean. Not yet: the stall rule, the unanswering backend, the fixture.
- 2026-11-01 (workspace, queue-reset cycle 2; the user chose no refactor and the stall test next):
  red = "a queue that stops changing ends the reset naming the stuck tasks" (a RUNNING task that
  never leaves RUNNING after its cancel) ran into its guard, `the reset did not stop on a stalled
  queue` — the loop had no end but success. Green: the reset keeps a signature of the queue state
  and every task's id, status and phase; the stall window (`stallMs`, 10 min by default) counts
  from the last change anywhere in it, as in `await-task.ts`, so a cancel the backend is still
  carrying out is not called stuck; at the window it throws `the queue made no progress for
  600000 ms while resetting it: task 7 clip-7.wav RUNNING`. Both reset tests pass on the fake
  clock; prettier and `tsc` clean.
- 2026-11-01 (workspace, queue-reset cycle 3; the user chose no refactor and confirmed this test):
  red = "a backend that does not answer makes the reset warn and step aside" failed with the
  snapshot's own `connect ECONNREFUSED 127.0.0.1:41234` — the reset let it escape and would have
  failed the spec under the fixture's name. Green: a snapshot that rejects makes the reset warn
  once, `the queue reset stepped aside: the backend does not answer (<message>)`, and return
  without canceling anything, so the spec fails on its own wait. All three reset tests pass on the
  fake clock; prettier and `tsc` clean. Next: the shared fixture and the full local journey.
- 2026-11-01 (workspace, queue-reset wiring; the user chose no refactor and confirmed the wiring):
  `e2e/tests/helpers/test.ts` exports `test`, Playwright's base test extended with an automatic
  `queueReset` fixture that runs `resetQueue` against the shared backend through the fixture's
  `request` (`GET /api/tasks` and `GET /api/queue` for the state, `POST /api/tasks/{id}/cancel`
  tolerating a 409 for a task that ended meanwhile, `POST /api/queue/resume`), and re-exports
  `expect`, `Page` and `Locator`; all 46 journey specs import from it (`gifs-record` keeps
  `@playwright/test`). The first full local journey passed (55 passed, 1 skipped, 9.1 min) but
  proved nothing: the fixture read `queue.paused` from `GET /api/tasks`, which returns only
  `{tasks}`, and the reset stepped aside before every spec on the TypeError — its catch-all took a
  wrong answer for no answer (discovered item appended above, to be confirmed). With the state read
  from `GET /api/queue`, the full journey passed again (55 passed, 1 skipped, 8.6 min) with no
  step-aside and no stall; `59-status-delete` took 1.7 s against 13.2 s. A throwaway run of
  `59-row-elapsed`, `59-status-delete`, `59-task-row` and `81-held-pause` with a log line in the
  fixture's cancel (removed afterwards) showed the reset canceling task 1 (jfk-ag, left running)
  before `59-status-delete` and tasks 3 and 4 (jfk-aa running, jfk-ab paused) before
  `81-held-pause`; 4 passed. The UI was rebuilt first (item 73's changes) and its digest recorded.
  e2e unit suite 27/27, prettier and `tsc` clean. Item 1 ticked.
- 2026-11-01 (the user, checkpoint after the wiring): the discovered item about a wrong answer is
  accepted and goes next; refactor approved: remove the six `59-*` idle-queue polls the fixture
  makes redundant (a change to the earlier plan to leave them for item 2). Refactor: the
  120-second `/api/tasks` polls at the start of `59-row-bars`, `59-row-color`, `59-row-controls`,
  `59-row-elapsed`, `59-header-typing` and `59-task-row` are gone, with the `appUrl` import they
  alone used (90 lines removed). Full local journey: 55 passed, 1 skipped, 9.8 min, no step-aside
  and no stall; prettier and `tsc` clean.
- 2026-11-01 (workspace, queue-reset cycle 4, the accepted discovered item): red = "a backend that
  answers wrongly fails the reset instead of making it step aside" — `Missing expected rejection`,
  the reset stepped aside on any error. Green: `reset-queue.ts` exports `BackendUnanswered`, and
  the reset steps aside only on it; any other error escapes. Playwright's rejection for a refused
  connection carries no `code`, only its message (`apiRequestContext.get: connect ECONNREFUSED …`,
  probed), so the fixture classifies instead of the reset sniffing text: its `getJson` turns a
  request that rejects into `BackendUnanswered`, and a non-2xx answer, a body that is not JSON, a
  list without a `tasks` array or a queue without a boolean `paused` into an error naming the request
  and what came back. The existing step-aside test now throws `BackendUnanswered` (its input stands
  for the fixture's classification; its assertions are unchanged). 4/4 reset tests, prettier and
  `tsc` clean. Throwaway smoke on `01-app-loads` (variants removed afterwards): a wrong path failed
  with `GET /api/queu answered 404:`; a dead port stepped aside with `GET /api/tasks:
  apiRequestContext.get: connect ECONNREFUSED 127.0.0.1:1` and the spec passed. Full local journey:
  55 passed, 1 skipped, 8.6 min, no step-aside, no stall, no wrong answer. Item ticked.
- 2026-11-01 (the user, checkpoint after cycle 4, answered "go on": the recommended options stand —
  no refactor; the `until` option goes next): red = "waiting until FAILED resolves when the task
  fails instead of rejecting" failed with `the task broken-status.wav failed: ffmpeg failed to
  encode the upload` — `awaitTranscription` only ever knew completion, so a wait for a failed row
  would have to keep a 120 s locator ceiling. Green: `AwaitTranscriptionOptions.until`, a predicate
  on the clip's row, default `status === 'COMPLETED'`, checked before the terminal-status rejection,
  so every existing caller keeps its behavior while a spec can wait for FAILED (or a running phase)
  by the task's own state, under the same whole-list stall rule. 4/4 `await-task` tests on the fake
  clock; prettier and `tsc` clean.
- 2026-11-01 (the user, checkpoint after cycle 5: share the stall watch; the "ends at once when the
  state can no longer arrive" test next): Refactor: `e2e/lib/wait.ts` gains `stallWatch(now)`, the
  one stall rule both waits use — each poll hands the state's signature and gets back how long it
  has gone unchanged, counted from the last change anywhere. `awaitTranscription` and `resetQueue`
  drop their own `signature`/`lastChange` bookkeeping for it; behavior unchanged. e2e unit suite
  29/29; prettier and `tsc` clean.
- 2026-11-01 (workspace, cycle 6): red = "a wait whose state can no longer arrive ends at once naming
  it" — a wait until DIARIZING for a row that jumped from TRANSCRIBING to COMPLETED sat out the
  stall window and blamed the queue (`the queue made no progress for 600000 ms while waiting for
  fast.wav (COMPLETED  100%)`). Green: once the row is COMPLETED and the predicate does not hold, the
  wait ends at the completion with `the task <clip> completed before reaching the state the wait
  wanted (COMPLETED)` — the second poll, not the tenth minute. 5/5 `await-task` tests; prettier and
  `tsc` clean.
- 2026-11-01 (the user, checkpoint after cycle 6: rename; then 59-status-delete first): Refactor:
  `awaitTranscription` → `awaitTaskState` and `AwaitTranscriptionOptions` → `AwaitTaskStateOptions`
  — the wait now ends on any row state, not only a transcription. Its three call sites follow
  (`lib/await-task.ts`, its test, `tests/helpers/ui.ts`). 5/5 `await-task` tests; prettier and
  `tsc` clean.
- 2026-11-01 (workspace, cycle 7, 59-status-delete first): red = the rewired spec
  (`waitForTaskState(page, STATUS_BROKEN_CLIP, (task) => task.status === 'FAILED')` in place of the
  row's 120 s `FAILED` ceiling) failed before running: `SyntaxError: The requested module
  './helpers/ui' does not provide an export named 'waitForTaskState'`. Green:
  `tests/helpers/ui.ts` gains `waitForTaskState(page, clip, until?)` — the task-list poll feeding
  `awaitTaskState`, `waitForTranscription` is that call without `until` — and the spec waits for the
  task's own failed state, then asserts the History row without a ceiling. Three local runs of the
  spec passed (3/3); `tsc` and prettier clean.
- 2026-11-01 (the user, checkpoint after cycle 7: inline the wrapper; then the RUNNING-and-
  TRANSCRIBING unit test): Refactor: `waitForTranscription` is gone — `waitForTaskState`'s default
  state is completion, so its 20 call sites across 16 specs and `helpers/ui.ts` call it directly
  (one name for the wait). Full local journey: 55 passed, 1 skipped, 8.7 min, no step-aside, no
  stall, no "completed before reaching"; `tsc` and prettier clean.
- 2026-11-01 (workspace, cycle 8, 81-held-pause; the user dropped the proposed unit test as a
  tautology over its own predicate and chose to wire the spec): the `Transcribing` wait (line 19, a
  120 s ceiling on the row's text) is now `waitForTaskState(page, HELD_PAUSE_CLIP, (task) =>
  task.status === 'RUNNING' && task.phase === 'TRANSCRIBING')`, and the row's text assertion
  follows without a ceiling. Three local runs of the spec passed (31.9 s, 33.8 s, 36.3 s); `tsc`
  and prettier clean.
- 2026-11-01 (workspace, item 2, group A part 1 — the `Transcribing` waits): the eight specs
  waiting on a row's "Transcribing" text (10-cancel-retry, 15-screenshots, 16-header-and-navigation,
  17-audio-while-transcribing, 40-atomic-transitions, 40-pause-queued, 59-row-elapsed, 59-task-row;
  ceilings 60–240 s) now wait on the task's own state, `waitForTaskState(page, clip, (task) =>
  task.status === 'RUNNING' && task.phase === 'TRANSCRIBING')`, and keep the text assertion without
  a ceiling. The eight specs together: 8 passed, 1.7 min (10-cancel-retry 23.6 s, 40-pause-queued
  20.4 s); `tsc` and prettier clean.
- 2026-11-01 (workspace, item 2, group A part 2 — the `FAILED` waits): 10-cancel-retry (60 s),
  12-history (60 s), 42-worker-stability (60 s) and 59-history-row (120 s, and the 30 s ceiling after
  its retry poll, which needed no wait of its own) now wait on `waitForTaskState(..., 'FAILED')` and
  keep their row assertions without a ceiling. The four specs: 4 passed, 52.2 s.
- 2026-11-01 (workspace, item 2, group A part 3 — the `QUEUED` waits): 59-row-controls (60 s) and
  59-task-row (60 s) wait on the task's own QUEUED state before the badge assertion. 2 passed,
  29.0 s.
- 2026-11-01 (workspace, item 2, group A part 4 — the running progress text): 59-row-bars and
  59-row-color waited for the row's `…` with a 120 s ceiling; each now waits for the task's own
  RUNNING state and then asserts the text with the default expect timeout, which still keeps the
  observation checks from passing before the row rendered. 5 passed (the four observation unit
  specs included), 32.3 s. Group A complete.
- 2026-11-01 (workspace, item 2, group B — the per-spec timeouts): the 19 `test.setTimeout` calls in
  14 journey specs (09-reassign 240 s; 40-atomic-transitions 600 s; 52-edit-journey, 52-edit-segment
  ×6, 52-keyboard-trial, 59-header 480 s; 59-header-typing, 59-row-controls, 59-row-elapsed,
  59-status-delete, 59-transcript-actions 300 s; 59-queue-options 240 s; 59-task-row 420 s;
  60-device-probe 360 s) are gone — the 20-minute journey timeout is the only ceiling left, and the
  waits that end a spec now end on the task's state. Full local journey: 55 passed, 1 skipped,
  9.1 min, no step-aside, no stall, no "completed before reaching". e2e unit suite 30/30; `tsc` and
  prettier clean. Item 2 ticked.
- 2026-11-01 (workspace, the list done but for item 3): `Status: in-review`. Item 3 (five
  consecutive `workflow_dispatch` runs of the final head on Windows (e2e) and Windows (packaged zip
  e2e), one at a time, no retry) is the user's to run on the tree this commit carries; it stays
  unticked until those runs are green.
- 2026-11-01 (the user, run 1 of 5): CI run #456 (`41347271166`, `workflow_dispatch` on
  `test/84-runs`, head `f2c3cf56`, tree `7cb8632`, `assets: false`): green, `CI (all checks)`
  success. `Windows (e2e)` 13.9 min (20:34:32 → 20:48:26; #452 had failed after 45.1 min),
  `Windows (packaged zip e2e)` 9.1 min, `Linux (e2e)` 11.3 min, plus every other job green and the
  four manual asset jobs skipped as a check-only dispatch intends. Four more dispatches of the same
  ref, no retry.
- 2026-11-01 (the user, run 2 of 5): CI run #458 (`41348949669`, `workflow_dispatch` on
  `test/84-runs`, head `f2c3cf56`, tree `7cb8632`, attempt 1): green, `CI (all checks)` success,
  no job failed. `Windows (e2e)` 13.9 min, `Windows (packaged zip e2e)` 9.3 min, `Linux (e2e)`
  13.1 min, `macOS (e2e)` 17.9 min. Three more dispatches of the same ref, no retry.
- 2026-11-01 (the user, run 3 of 5 — red): CI run #459 (`41350889260`, same head and tree,
  attempt 1): `Windows (e2e)` failed at `10-cancel-retry.spec.ts:13` after 3.7 s while the rest of
  the suite kept running, so the job was still inside `gradlew.bat e2e` at 35 min and its trace
  upload had not run yet; `Linux (e2e)` 10.0 min, `Windows (packaged zip e2e)` 9.4 min, `macOS
  (e2e)` 16.1 min green. The streak restarts once the cause is fixed. Discovered item appended:
  a run must end at the first failing spec. Change: `maxFailures: 1` in `e2e/playwright.config.ts`
  (journey) and `e2e/playwright.desktop.config.ts`, with a comment naming the item. Proof, a planted
  failing spec ahead of `01-app-loads`: `1 failed`, `1 did not run`; the plant was removed. The
  cause of the 3.7 s failure is still being read from the run's trace — no change made on a guess.
- 2026-11-01 (the user, checkpoint after the fail-fast change): the bounded browser-side promise is
  the next cycle, in the order 81-held-pause, 59-connection, 02-mascot-and-loading; run #459's job is
  left to end on its own so its log keeps the two failure details. Cycle: red = "a promise that
  never settles is reported naming what was awaited" (`SyntaxError: … does not provide an export
  named 'withinDeadline'`). Green: `e2e/lib/bounded.ts` gains `withinDeadline(promise, {what, ms,
  intervalMs?, now?, sleep?})` — the promise's value when it settles in time, its rejection when it
  rejects in time, and `waited <ms> ms for <what> and it never came` when the deadline passes first,
  driven by the fake-clock seams like the other waits. 2/2 `bounded` tests; prettier and `tsc`
  clean. Not yet: the value case, the three call sites.
- 2026-11-01 (workspace, the same item): the value case's test — "a promise that settles in time
  keeps its value and its rejection" — went red on a real defect: after the answer arrived the
  polling deadline kept advancing the clock (1250 ≠ 1000), i.e. a race loop outliving the settled
  promise. Green: `withinDeadline` is that file's own pattern — a `setTimeout` deadline raced with
  the promise and cleared in `finally`, the promise's value returned unwrapped and its rejection
  rethrown — with both tests on `t.mock.timers`. Wired: 81-held-pause's `await answered.promise`
  (the Pause request must reach the backend, 60 s), and the two held route streams (59-connection's
  `held`, 02-mascot-and-loading's `firstStream`, 60 s each). 3/3 `bounded` tests; the three specs
  3 passed (47.7 s); `tsc` and prettier clean.
- 2026-11-01 (the user, run 3 of 5 — the failure read from its job log): CI run #459 is red:
  `Windows (e2e)` 47.2 m, 53 passed, 1 skipped, 2 failed, `CI (all checks)` failure; every other job
  green. The two failures, with their real messages:
  `10-cancel-retry.spec.ts:45` → `Error: the task jfk-cancel.wav canceled` thrown at
  `lib/await-task.ts:74` — after Retry, the wait for the retried run's completion took its first
  snapshot before the retry left the old CANCELED run, and a terminal status ends the wait. Not
  reproducible locally (8/8 passes, 41–59 s each), so it is a rare race, not slowness.
  `59-task-row.spec.ts:71` → `expect.poll(percent, { timeout: 120_000 })` saw `0` for two minutes:
  a wall-clock ceiling on transcription progress that had simply not moved yet on that runner.
  `81-held-pause` took 6.2 m (slow, not wedged) — the job's 47.2 m is the suite, not a hang.
- 2026-11-01 (the user, approving item A: the wait remembers the run it replaces; item B: state wait
  with a ceiling kept): red = "a wait that replaces a canceled run resolves on the new run" —
  `Error: the task retried.wav canceled` on the first snapshot. Green: `TaskSnapshot` gains the
  API's own `finishedAt`, and `AwaitTaskStateOptions` gains `after` — the run being replaced; while
  the row still carries that run's finish, the wait passes over it. The obvious alternative, a wait
  that remembers the status it first saw, was rejected on evidence: it breaks the pinned test that
  an already-FAILED task ends the wait at once. `waitForTaskState` passes `after` through;
  10-cancel-retry reads the canceled run's `finishedAt` before it clicks Retry. 6/6 `await-task`
  tests, 4/4 reset tests; the spec 3/3 (build was already current). Item A ticked.
- 2026-11-01 (workspace, item B): `59-task-row`'s `expect.poll(percent, { timeout: 120_000 })`
  becomes `waitForTaskState(page, RUNNING_ROW_CLIP, (task) => task.transcriptionProgress > first,
  { stallMs: 120_000 })` — the percent advancing ends it at once, and 120 s without a change anywhere
  in the task list ends it naming the row, so the ceiling stays but no longer fires on a slow runner
  that is making progress. `waitForTaskState` passes `stallMs` through. 2/2 local runs. Item B
  ticked.
- 2026-11-01 (workspace, after items A and B, the bounded promises and fail-fast): full local
  journey green — 55 passed, 1 skipped, 8.0 min, no step-aside, no stall message, no "completed
  before reaching". The test list is done but for the five-run item, which is the user's to run on
  this tree; `Status: in-review`.
- 2026-11-01 (workspace, item 2, group C — the remaining ceilings, and the replaced-run precedence):
  twelve more waits stopped reading a wall clock and started reading the task: 10-cancel-retry (the
  retried broken clip's row leaving, 60 s) waits for that clip's own `FAILED`; 17-audio-while-
  transcribing (120 s) for `CANCELED`; 40-atomic-transitions' raced row (120 s) for `COMPLETED` or
  `CANCELED` and its follow-up upload (120 s) for completion; 40-pause-queued (120 s) for
  `RUNNING`+`TRANSCRIBING`; 59-header-typing and 59-row-controls (120 s each) for `RUNNING`;
  59-task-row waits for `CHUNKING` and its `expect.poll` keeps 100 ms intervals but loses its 180 s
  ceiling; 59-queue-options' task-creation poll (60 s) for the row existing; 59-history-row's retry
  poll (120 s) for the retried run's `FAILED` with `after: failedAt`. `waitForTaskState` gains
  `intervalMs` for one case: 59-favicon's fixture is a 2–8 s trim by design, so its running window
  is a couple of seconds — a 1 s poll missed it and the wait correctly rejected with "completed
  before reaching", so that spec polls at 100 ms (5/5 local runs). 59-history-row's conversion
  needed `awaitTaskState` to pass over a replaced run *before* the predicate is consulted, not after:
  the predicate `status === 'FAILED'` matched the stale row. Cycle: red = "the run being replaced is
  passed over even when the predicate matches it" (`AssertionError: the stale FAILED row did not end
  the wait` with the old order); green: the precedence swapped and the one added test; 7/7
  `await-task` tests, e2e unit suite 34/34; `tsc` and prettier clean. Full local journey after all of
  it: 55 passed, 1 skipped, 8.1 min, no step-aside, no stall message, no "completed before reaching".
  The log's previous tail was repaired: run 1's "ref, no retry" and item B's "ticked" had been cut
  off and stranded at the end of the file. Item 3 stays unticked — it is the user's five dispatches.
- 2026-11-01 (the user, the new streak, run 1 of 5): CI run #462 (`41359810906`, `workflow_dispatch`
  on `test/84-runs`, head `e2593eb5`, tree `7ae1e3e9`): green, `CI (all checks)` success.
  `Windows (e2e)` 12.0 min (23:03:57 → 23:15:55Z; #452 had failed after 45.1 min), `Windows
  (packaged zip e2e)` 6.6 min, `Linux (e2e)` 14.2 min, `macOS (e2e)` 16.7 min. Dispatched after a
  first attempt (#461, `41359547603`) turned out to carry a stale tree (`8aee7277`): the user's
  `git rev-parse` resolved against an old branch pointer, the commit was rebuilt from the literal
  tree `7ae1e3e9fbe90e260f218272096fd12c9065ac96`, and #461 was cancelled. Four more dispatches of
  the same ref (`e2593eb5`), no retry.
- 2026-11-01 (the user, run 2 of 5 — red, and the cause read from its trace): CI run #463
  (`41361840478`, same ref and tree): `Windows (e2e)` failed at `04-reorder.spec.ts:19` after 46 s
  with the fail-fast (`1 failed`, `52 did not run`); `Windows (packaged zip e2e)`, `Linux (e2e)`,
  `macOS (e2e)` and `CI (all checks)` green. The trace artifact shows three `POST /api/tasks` 201,
  then `PUT /api/queue/order` answered **409** with a 17-byte body (two ids), and the page snapshot
  carries the server's own message `taskIds must be exactly the pending tasks ([4, 5, 6])` with two
  rows rendered. Cause: `ProcessingEngine.queueUpdated` read `pendingOrderedIds()` and then emitted,
  unserialized, so two concurrent uploads' snapshots crossed and the older one arrived last;
  `connectionMachine` applies `queue-updated` wholesale and `reorderMachine.reconcile` dropped the
  omitted id. The streak restarts at 0.
- 2026-11-01 (workspace, the user's checkpoint: both the mutex and the client guard): red = the new
  `QueueView` test "keeps the row of a task a queue snapshot omits while that task is still pending"
  (`AssertionError: expected [ …(2) ] to deeply equal [ Array(3) ]`). Green: `reorderMachine`'s SYNC
  carries the pending ids and the displayed order is that set — the server's own
  `pendingOrderedIds` — ranked by the queue event and then by the local order; `QueueView` passes
  the ids of the tasks that are not finished as one string, so a progress tick cannot clear a
  reorder error the user is reading; `queueUpdated` builds and publishes its snapshot under
  `queueEventsLock`. Two existing tests modelled a queue snapshot that drops a task without ever
  announcing it finished — both now carry the sequence the server really sends, and the
  finished-task one also pins that the still-queued row stays. 279/279 frontend tests, `gradle
  check` exit 0, full journey 55 passed, 1 skipped, 8.1 min, no step-aside and no stall; prettier and
  `tsc` clean; the UI was rebuilt and its digest recorded. The mutex is defensive and has no test of
  its own: forcing the interleaving needs a seam inside the engine, so the client test is the
  deterministic proof. Item ticked.
- 2026-11-02 (workspace, refactor checkpoint after the queue-order green; the user approved all
  three): `finishedStatus` and its complement `pendingStatus` moved out of `QueueView` into
  `frontend/src/shared/api/types.ts` beside `TaskStatus`, replacing the view's two local status
  arrays; the sync's `pendingIds`/`pendingKey` pair collapsed into one derived key, so a progress
  tick no longer rebuilds an array it does not use; and `reconcile`'s ranking became
  `rankedOrder(queueOrder, localOrder, pending)`. No behavior change: 279/279 frontend tests after
  each step, `tsc` and prettier clean, and a full journey after the three — 55 passed, 1 skipped,
  8.0 min, no step-aside and no stall. The UI was rebuilt and its digest recorded.
- 2026-11-02 (the user, the Windows target widened to the job budget): run #464's first 57 minutes
  showed a runner whose whisper-cli is about ten times slower than #462's — 03-upload-queue 11.8 s →
  1.4 min, 06-playback 33.9 s → 6.1 min, 10-cancel-retry ~40 s → 11.5 min — while every DOM-only spec
  ran at #462's speed (`01-app-loads` 1.9 s vs 2.3 s, `04-reorder` 1.5 s vs 1.6 s, `07-downloads`
  1.2 s vs 1.0 s), and nothing failed. The user's decision: the five-run bar counts a green run that
  finishes inside the `Windows (e2e)` job's 90-minute budget, and the 13–20 min band stays the
  measurement the item reports, not a second gate. The acceptance criteria now say so; wall time is
  item 85's subject. Residual risk, recorded rather than hidden: a runner still slower than #464's
  would hit the job timeout, which is exactly the measurement item 85 starts from.
- 2026-11-02 (the user, run 1 of 5 on the fixed tree — red on a slow runner): CI run #464
  (`41365055365`, head `2f7e46f9`, tree `1f25f995`): `Windows (e2e)` failed at
  `59-task-row.spec.ts:72` — `the queue made no progress for 120000 ms while waiting for jfk-aa.wav
  (RUNNING TRANSCRIBING 0%)` — with `49 passed, 1 failed, 5 did not run (59.0m)`. That runner's
  whisper-cli was about ten times slower than #462's (03-upload-queue 11.8 s → 1.4 min, 06-playback
  33.9 s → 6.1 min, 10-cancel-retry ~40 s → 11.5 min) while DOM-only specs ran at #462's speed.
  59-task-row was the only wait in the suite with a `stallMs` of its own; the 49 specs that ran
  before it used the shared 600 s window — `10-cancel-retry` 11.5 m, `59-row-color` 7.4 m,
  `17-follow-mode` 7.0 m, `40-pause-queued` 6.9 m among them — and none tripped it. The streak
  restarts at 0.
- 2026-11-02 (workspace, the user's question: shorter clips, and the wait's window): the fix has
  two parts, because they answer different problems. (1) The wait now uses the shared 600 s window
  and compares the row's text percentage with the API's 0–1 fraction in the same unit — the old
  predicate `task.transcriptionProgress > first` was unsatisfiable for any row that started above
  0 %, and the 120 s window fired on a task that was working. (2) `jfk-aa` becomes a 7× loop
  (~77 s) instead of 16× (~176 s), its content still unique (2, 3, 4, 5, 6, 8–17, 20 are taken by
  other specs): it is the journey's longest clip, and shortening it cuts a slow runner's compute
  without changing what the spec needs — a running row, one advancing percentage, QUEUED and
  PAUSED neighbours. Chunks are cut at silences (ADR 0004) and progress is reported per chunk and
  per whisper progress line, so clip length does not set the observed gap; the window does. Spec
  3/3 locally, `lib/fixtures.test.ts` green, full journey 55 passed, 1 skipped, 7.9 min, `tsc` and
  prettier clean. Item ticked.
- 2026-11-02 (the user, run 1 of 5 on the fixed tree — red at a restart, not at a wait): CI run #465
  (`41372436534`, head `2895e55c`, tree `9f7b6438`): `Windows (e2e)` failed at
  `17-settings.spec.ts:104` — `the queue made no progress for 600000 ms while waiting for jfk-p.wav
  (no task row yet)` — with `19 passed, 1 failed, 36 did not run (15.3m)`. The trace shows the cause
  to the millisecond: `POST /api/tasks` (the spec's upload) at 01:35:52.025 answered **-1 after
  635 ms** (aborted), `/api/events` went -1 at 01:35:55 as the spec's own `stopBackend` killed the
  backend, the stream reconnected at 01:35:58, `/api/queue/resume` answered 200 at 01:35:59, and from
  01:36:00 the wait polled `/api/tasks` for ten minutes with no such task; the page snapshot shows
  `No tasks yet`. `uploadClips` only sets the file input, so on that runner the upload was still in
  flight when the stop arrived — a race the spec had always won at ~100 ms and lost at ~635 ms. The
  streak restarts at 0.
- 2026-11-02 (workspace, the same run's fix): `17-settings` waits for the clip's own `QUEUED` state
  (the queue is paused, so an accepted upload waits there) before `stopBackend`, so the backend is
  never killed under an in-flight upload; `13-restart` already did the equivalent by waiting for its
  two rows. Spec 2/2 locally, full journey 55 passed, 1 skipped, 8.0 min, `tsc` and prettier clean.
  Item ticked.
- 2026-11-02 (the user, run 1 of 5 on tree `31778705` / head `98ca176b`): CI run #469
  (`41376056669`): green, `CI (all checks)` success. `Windows (e2e)` 17.2 min — inside the 13–20
  band, so this is a normal runner — `Windows (packaged zip e2e)` 9.8 min, `Linux (e2e)` 12.5 min,
  `macOS (e2e)` 15.4 min. Four more dispatches of the same ref, no retry.
- 2026-11-02 (the user, run 2 of 5 — red, and a frontend race this time): CI run #471
  (`41378393242`, same head and tree as #469): `Windows (e2e)` failed at `59-queue-options.spec.ts:76`
  — `expected 'de' to be 'en'` — with `40 passed, 1 failed, 14 did not run (6.5m)` on a fast runner.
  The task list the spec read (the trace's own response body) has the created task as
  `id=37 jfk-y.wav lang=en mode=fixed count=2 max=3`: three of the four chosen options reached the
  task and the language reverted to the default, which is the signature of `QueueView`'s prefill
  effect running after the user's language click — it applied all four stored defaults once, with no
  knowledge of what the user had already changed. Green in #469 on the same tree. The streak
  restarts at 0.
- 2026-11-02 (workspace, the user's choice: track the fields separately): red = the new
  `QueueView` test "prefills only the options the user has not touched while the settings load" —
  `Expected the element to have value: german / Received: english` with `/api/settings` held open
  while the language is picked. Green: `QueueView` remembers which of the four options the user
  changed (`batchTouched`, one set) and the prefill effect sets only the others, once per stored
  settings object. 280/280 frontend tests, `tsc` and prettier clean, full journey 55 passed,
  1 skipped, 8.5 min; the UI was rebuilt and its digest recorded. (One detour worth keeping: the
  first comment said `run #471`, which the design-value convention read as a literal color — it now
  says `CI run 471`.) Item ticked.
- 2026-11-02 (workspace, the acceptance audit before the streak finishes; the user chose to name the
  three remaining windows rather than restart the streak): every criterion holds but the wording of
  one. `windows-e2e` is in `CI (all checks)`'s `needs` (`ci.yml:1362`); the measurements are in this
  log (#469 17.2 min, run #464's runner 59.0 min); the journey cannot run against a stale UI; the
  settings draft race — the Settings page's and, from this branch, the upload options' — is fixed;
  no e2e row remains in `docs/known-issues.md`; the test list is ticked but the five-run gate; the
  status is `in-review`. The exception: three `page.waitForTimeout` calls are still in journey specs
  (the GIF recorder's are out of scope) — `40-pause-queued.spec.ts:37` (3 s before asserting a paused
  task was not started), `52-edit-segment.spec.ts:261` (200 ms before asserting the page under a
  dialog did not scroll) and `59-header.spec.ts:109` (the 250 ms cadence of a sampling loop). Two are
  negative claims, where only an interval can show that nothing happened, and the third samples a
  playing position; the criterion now says exactly that instead of claiming there are none. Code
  unchanged, so the runs on tree `339bd1e7` still count.
- 2026-11-02 (the user, run 1 of 5 on tree `339bd1e7` / head `450a675e`): CI run #473
  (`41380923490`): green, `CI (all checks)` success. `Windows (e2e)` 11.5 min, `Windows (packaged zip
  e2e)` 9.9 min, `Linux (e2e)` 12.6 min, `macOS (e2e)` 17.5 min — a fast runner, and the first run
  where the upload-options prefill race is present. Four more dispatches of the same ref, no retry.
- 2026-11-02 (the user, run 2 of 5 — red in the harness, then a code audit): CI run #475
  (`41383416059`, same head and tree): **every spec passed** — `55 passed, 1 skipped (10.6m)` — and
  the job failed in global setup's teardown with `EPERM` on `rmSync(dataDir)` at
  `global-setup.ts:42`: Windows still held a handle on the stopped backend's data directory. The
  streak restarts at 0. The user's question was whether the remaining races can be found by reading
  the code, so a read-only audit (18 findings, each with file:line) was run over the whole suite.
- 2026-11-02 (workspace, the audit's fixes): three cycles. (1) Teardown: `lib/cleanup.ts` removes a
  directory with bounded retries while the OS says it is busy and reports a leftover instead of
  throwing; `global-setup.ts` and `60-device-probe` use it. (2) No spec proceeds on an answer it has
  not read: `uploadClips` counts one answer per file (equal `waitForResponse` predicates all resolved
  on the first answer, so later files went unchecked), a new `moveRowDown` waits for the reorder's
  200 before 04's reload and 13's backend stop, 17-settings polls the whole stored settings object
  before the stop, 10-cancel-retry's two retries wait for their 200, 15-screenshots asserts the
  in-flight upload's 201, 09-reassign waits for its `speakers-reassigned` event, 14-contract waits
  for the bodies it conforms, `ensureTranscript` decides from the task list instead of a 5 s page
  probe, and `rowOrder` reads only the pending list. (3) Shared state: 05/07/08 derive their task id
  from the list (and upload the clip when absent) instead of assuming ids 1/2 from `03`;
  59-transcript-actions renames its shared clip's speaker back. Two mistakes of mine on the way, both
  caught before the hand-over: two journeys were run at once (they share `test-results`, so one lost
  its traces — never again), and the new settings poll compared the form's language *label* with the
  API's *code* (`german` vs `de`). Final: 37/37 e2e unit tests, `tsc` and prettier clean, full
  journey 55 passed, 1 skipped, 8.0 min.
- 2026-11-02 (the user, run 1 of 5 on the audited tree `6792b78c` / head `e43ad820`): CI run #478
  (`41388614113`): green, `CI (all checks)` success. `Windows (e2e)` 13.9 min, `Windows (packaged zip
  e2e)` 7.6 min, `Linux (e2e)` 14.1 min, `macOS (e2e)` 16.5 min. Four more dispatches of the same
  ref, no retry.
- 2026-11-02 (the user, run 2 of 5, same ref): CI run #479 (`41390517947`): green, `CI (all checks)`
  success. `Windows (e2e)` 13.9 min, `Windows (packaged zip e2e)` 9.2 min, `Linux (e2e)` 9.5 min,
  `macOS (e2e)` 15.6 min. Three more dispatches, no retry.
- 2026-11-02 (the user, run 3 of 5, same ref): CI run #480 (`41392250048`): green, `CI (all checks)`
  success. `Windows (e2e)` 11.8 min, `Windows (packaged zip e2e)` 9.3 min, `Linux (e2e)` 13.2 min,
  `macOS (e2e)` 13.8 min. Two more dispatches, no retry.
- 2026-11-02 (the user, run 3 of 5 — red on Linux, the class the audit had flagged): CI run #481
  (`41393962423`, same ref): `Linux (e2e)` failed at `59-task-row.spec.ts:32` — `the task jfk-aa.wav
  completed before reaching the state the wait wanted (COMPLETED)` — with `49 passed, 1 failed, 5 did
  not run (7.7m)`; `Windows (e2e)` 9.7 min, `Windows (packaged zip e2e)` 9.3 min and `macOS (e2e)`
  15.1 min green, `CI (all checks)` red because of Linux. On that runner the 77 s clip finished
  between two one-second polls, so the wait for `phase === 'CHUNKING'` never saw the phase — audit
  finding 8, now with a real failure behind it. The streak restarts at 0 (the item's gate is "CI
  (all checks) green").
- 2026-11-02 (workspace, the observation fix): both specs that read a transient state now read a
  page-side record — `59-task-row` samples its row on every DOM change (MutationObserver) from before
  the resume and asserts from the record that a chunking line rendered with an indeterminate bar and
  no RUNNING badge, and that a transcribing line's percentage advanced; `59-favicon` records the
  `link[rel=icon]` href the same way and asserts the typing artwork appeared and the static one is
  what the page links at the end. The two collector-driven specs drop their `toContainText('…')` on
  the running row: their own observations prove the row rendered, and that read could race the
  finish. Spec 3/3 (row) and 5/5 (favicon) locally, full journey 55 passed, 1 skipped, 8.1 min;
  `tsc` and prettier clean. The known-issues row for these four specs is removed. Item ticked.
- 2026-11-02 (the user, run 1 of 5 on the observation-fixed tree `308c8997` / head `128dafb9`): CI
  run #483 (`41397259410`): green, `CI (all checks)` success. `Windows (e2e)` 14.8 min, `Windows
  (packaged zip e2e)` 8.8 min, `Linux (e2e)` 14.0 min, `macOS (e2e)` 17.9 min. Four more dispatches
  of the same ref, no retry.
- 2026-11-02 (the user, run 2 of 5, same ref): CI run #486 (`41399486666`): green, `CI (all checks)`
  success. `Windows (e2e)` 15.8 min, `Windows (packaged zip e2e)` 11.4 min, `Linux (e2e)` 12.4 min,
  `macOS (e2e)` 19.5 min. Three more dispatches, no retry.
- 2026-11-02 (the user, run 3 of 5, same ref): CI run #488 (`41402481299`): green, `CI (all checks)`
  success. `Windows (e2e)` 12.0 min, `Windows (packaged zip e2e)` 8.4 min, `Linux (e2e)` 12.9 min,
  `macOS (e2e)` 15.0 min. Two more dispatches, no retry.
- 2026-11-02 (the user, run 4 of 5, same ref): CI run #489 (`41405050086`): green, `CI (all checks)`
  success. `Windows (e2e)` 14.5 min, `Windows (packaged zip e2e)` 10.2 min, `Linux (e2e)` 13.0 min,
  `macOS (e2e)` 15.9 min. One more dispatch, no retry.
- 2026-11-02 (the user, run 5 of 5, same ref — the gate is met): CI run #490 (`41409981600`): green,
  `CI (all checks)` success. `Windows (e2e)` 11.7 min, `Windows (packaged zip e2e)` 6.5 min, `Linux
  (e2e)` 13.6 min, `macOS (e2e)` 17.1 min. The five consecutive runs on tree `308c8997` (head
  `128dafb9`) are #483, #486, #488, #489, #490 — `Windows (e2e)` 14.8, 15.8, 12.0, 14.5 and
  11.7 min, `Windows (packaged zip e2e)` 8.8, 11.4, 8.4, 10.2 and 6.5 min, every run's `CI (all
  checks)` green, no retry, one dispatch at a time. The test list is complete and every item is
  ticked; the only change on the branch since the measured tree is this log (and the item's own
  file), which is what the counting rule allows. **Item 84 is ready for the root agent's squash and
  PR; `Status: in-review`.**
- 2026-11-02 (root agent, at the user's request): the review's two nits are fixed, so the measured tree no
  longer matches the code and the five-run gate is open again. (1) `59-task-row`'s "the percentage
  advances" asserted only that a non-zero percentage appeared; it now requires the row to have
  rendered more than one percentage and the last to be past the first, from the same page-side
  record. (2) A second journey started from the same checkout while one runs now fails at global
  setup naming the live pid and the state file, instead of starting a backend over the first one's
  (the local cascade of `ECONNREFUSED` where two runs shared `e2e/.e2e-state.json`); a leftover
  state whose process is gone, or an unreadable one, does not block a run. Unit tests in
  `e2e/lib/backend.test.ts`; the runs #483, #486, #488, #489 and #490 measured tree `308c8997` and
  no longer count. Five dispatches of the new head are the gate; `Status: in-review`.
- 2026-11-02 (root agent, PR preparation, at the user's request): the review's two nits are fixed
  (commits `7f7ab591`, `c822edb6`), so the measured tree `308c8997` no longer matches the code and
  the five-run gate is open again: runs #483, #486, #488, #489 and #490 do not count, and five
  `workflow_dispatch` runs of the head this commit carries are the gate before merging. (1)
  `59-task-row`'s "the percentage advances" asserted only that a non-zero percentage appeared; it
  now requires the row to have rendered more than one percentage and the last to be past the first,
  read from the same page-side record. (2) A journey started from a checkout where another one is
  running now fails at global setup, naming the live pid and the state file, instead of starting a
  backend over the first run's (the local cascade of `ECONNREFUSED` when two runs shared
  `e2e/.e2e-state.json`); a leftover state whose process is gone, an unreadable one, and one without
  a pid do not block a run. Unit tests in `e2e/lib/backend.test.ts` (40 in the package). Verified by
  exit code: the row spec alone 0 (23.3 s), the full journey 0 (55 passed, 1 skipped, 8.0 m), e2e
  `tsc` and prettier 0, e2e `node --test` 0. The item's spec and final test list moved from
  `roadmap/84-e2e-reliability.md` to `docs/specs/e2e-reliability.md` with `Status: done`, README row
  and graph node, item 78's dependency and row, item 85's dependency and prose, and the branch is
  squashed to one commit on `main` (ADR 0050). The gate line stays unticked until the five runs
  exist; ticking it is a log-only change, which the counting rule allows.
- 2026-11-02 (the user, five runs of the final head `f4e91b6d`): the gate is met — five consecutive
  whole-run CI runs, one at a time, no retry, every one's `CI (all checks)` green and no job failed:
  #491 (pull_request, `41416620147`) `Windows (e2e)` 13.6 min and `Windows (packaged zip e2e)`
  7.7 min; #492 (`41419641560`) 16.2 / 12.6; #494 (`41423110209`) 13.9 / 8.8; #495
  (`41425948857`) 17.3 / 10.5; #496 (`41430386916`) 14.4 / 10.3. The gate's wording moves from
  `workflow_dispatch` runs to whole-run CI runs because the user's series counted the
  pull-request run of the same head as its first; the head never changed between the five. **Item
  84 is done**; this entry and the tick are the only changes since the measured tree.
