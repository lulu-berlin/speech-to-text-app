# 84 — e2e reliability: a Windows journey that can gate again, no wall-clock or sleep-based assertions, and a journey that tests the current UI

Status: spec-approved
Depends on: —
Blocks: [78](78-stable-release.md) (a 1.0 gate: a flaky journey weakens release verification, and
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
  consecutive green runs**, each journey step finishing within **13–20 min**.
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

- [ ] (measure) The Windows and Linux journeys publish per-spec durations from the same commit as a
      CI artifact, and the comparison (the specs and waits that cost Windows its time) is recorded
- [ ] (Windows) The Windows journey passes 5 consecutive runs, each within 13–20 min, and rejoins
      `CI (all checks)`; the gating check lists no reporting-only job any more
- [ ] (harness) A backend that exits during startup fails the start naming its exit, not a health
      timeout, and the test holds no wall-clock bound
- [ ] (harness) The journey rebuilds the UI when any build input changed — a source file, a config,
      the lockfile or a `docs/api` example the app imports — and reuses `dist` when nothing did, on
      both the Gradle and the direct Playwright path
- [ ] (specs) `06-playback` and `17-follow-mode` use no fixed sleep as an assertion; a regression
      that lands after the former sleep window fails them
- [ ] (specs) `gifs-record` waits for state by signals; its deliberate pacing pauses remain
- [ ] (app) After choosing `fixed` and typing the count, a pending earlier write's answer leaves both
      the mode and the count as typed, and the count field stays mounted (unit test of the race)
- [ ] (e2e, ADR 0046) `17-settings` passes on every platform's journey with the fix in place
- [ ] (must not break) The Linux and macOS journeys stay green with the same spec count, and the
      packaged desktop suites are unchanged

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
