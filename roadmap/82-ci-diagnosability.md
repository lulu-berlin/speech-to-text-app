# 82 — A red CI job is diagnosable from CI alone, and a check job does not fail for the network

Status: spec-approved
Depends on: —
Blocks: —

## Goal

When CI fails, the failure can be diagnosed from the workflow run itself: the failing spec's trace,
screenshot and error context are attached to the run, and a runner that cannot reach a third-party
host fails only the jobs that genuinely need that host — never the lint or documentation checks.
Every flaky observation recorded so far becomes deterministic, instead of being re-reported by hand.

This is the umbrella for the CI friction seen on 2026-10-23 and 2026-10-24: the infrastructure the
diagnoses need, and the recorded CI flakes themselves (the consultation's scope decision).

## Evidence

Line numbers are `main` after items 54, 65 and 68.

- **Failing e2e runs keep nothing.** Playwright is configured with `trace: 'retain-on-failure'`
  (`e2e/playwright.config.ts:24`, `e2e/playwright.desktop.config.ts:13`) and `reporter: 'list'`
  (`:14`, `:9`), so `test-results/` holds a trace, a screenshot and `error-context.md` for every
  failure — and no workflow step uploads it. The only `actions/upload-artifact` steps in
  `.github/workflows/ci.yml` are the DMG (`:399`) and the four manual asset jobs (`:450`, `:517`,
  `:857`, `:951`); the jobs that run Playwright specs — `e2e` (`:148`), `macos-e2e` (`:227`),
  `macos-dmg` (`:287`), `windows-desktop-zip` (`:570`), `desktop-e2e` (`:963`), `desktop-nix-e2e`
  (`:1066`) and `desktop-bundle-e2e` (`:1097`) — upload nothing on failure. Every diagnosis of a red
  e2e job so far (#212, #216, #223, #236, #239, #246, PR #66) needed the owner to paste the console
  log, and the traces that would have settled the question were discarded with the runner.
- **A check job failed because a runner could not reach github.com.** Run #246
  (`docs/known-issues-e2e-flakes`, a Markdown-only change) failed `macOS (Nix build + devenv)` in its
  `actionlint the workflows` step (job 120645940071) without actionlint ever running:
  ```
  Building whisper-cpp-1.8.4-aarch64-darwin.tar.gz
  desktop artifact … missing: curl: (7) Failed to connect to github.com:443 after 57 ms
  ✖ Evaluating shell in 30.3s (failed) → Failed to realize shell derivation: 1 dependency failed
  ```
  The step runs `devenv shell -- actionlint .github/workflows/*.yml` (`ci.yml:264-265`, and the
  Linux twin `nix-devenv` at `:1144-1145`); the shell realizes the pinned whisper-cpp/ffmpeg assets,
  which are GitHub releases of this repo, so linting needs egress to github.com.
- **Saving the Nix cache on failure is not a fix.** The same job logged a primary-key miss and a
  `restore-prefixes-first-match: nix-macOS-` miss, then skipped the cache post-step because the job
  failed (`nix-toolchain/action.yml` passes `save: ${{ inputs.save-cache }}`). That job is macOS's
  only cache writer (`ci.yml:261`); item 53 made one job per key the writer because a key is
  immutable, so a failed run saving would lock an incomplete store under the key.
- **The always-on docs job needs the npm registry since item 54.** `docs-check` (`ci.yml:110`)
  installed nothing and ran Node alone; item 54 made it a pnpm package, so the job runs
  `pnpm --dir docs install --frozen-lockfile` before `pnpm --dir docs test`. It is the one job a
  Markdown-only pull request still runs besides `changes` and `CI (all checks)`, so a registry outage
  reddens the required check on a pull request that changes no code. The node-toolchain action's
  pnpm store cache (keyed on `docs/pnpm-lock.yaml`) narrows this to cold caches (item 54's review).
- **Node 20 deprecation.** Run 40099483165 annotates every `actions/upload-artifact@v4` step (and an
  `actions/cache@v4` inside `windows-whisper-asset`) as "Node.js 20 is deprecated … forced to run on
  Node.js 24". The workflow's own `actions/cache` steps are already `@v6` (`ci.yml:304`, `:587`,
  `:983`, `jvm-toolchain/action.yml:68`).
- **The recorded CI flakes** (moved here from `docs/known-issues.md`, which keeps no row for planned
  work):
  - `13-second-quit`: the packaged-desktop quit spec's `finally` reads `app.process()` after the app
    exited and throws `TypeError: Cannot read properties of undefined (reading '_object')`, failing a
    spec whose assertions passed and masking the outcome
    (`e2e/desktop-tests/13-second-quit-during-shutdown.spec.ts:33`, `await exited` at `:29`). Seen in
    runs 39540019816 and 39891003455 (job "Desktop (packaged .deb e2e)") after the same bytes passed;
    item 78 lists it among the MED issues a stable release must close, and its row now points here.
  - `ProcessRunnerSpec` "a pause() issued while the process is being spawned": `List should be empty
    but has 3 elements, first being: "1"` — the pause landed after `countingLoop`'s 500 ms head start
    (`backend/src/test/kotlin/stt/process/ProcessRunnerSpec.kt:521`); 1 of 413 integration tests on
    macOS, run 40041313804; the same job passed on runs 40041462244 and 40039983395.
  - `59-row-bars`: `toHaveCount(2)` after the `Diarizing… \d+%` text appears failed once without two
    bars (`e2e/tests/59-row-bars.spec.ts:37-38`; run 39978912415, commit `e236ebb1`). Likely trigger
    `[INFERENCE]`: the short clip's diarizing phase ends, and the row leaves the queue, between the
    text check and the count check; `toHaveCount` already retries for the 20 s expect timeout.
  - `52-keyboard-trial` on macOS: after Escape lands on the current segment, one Tab does not reach
    its Edit control — `expect.poll(focused).toMatchObject({ ariaLabel: 'Edit segment' })` received
    `ariaLabel: null` for the whole 20 s (`e2e/tests/52-keyboard-trial.spec.ts:56-57`; job
    `macOS (e2e)` on PR #66, merge ref `b9feb0d`, 51 of 52 specs passed). No trace was kept. Whether
    `macOS (e2e)`'s failure in run 40099483165 was the same spec is unknown.
  - Item 65 owns `59-row-color` (done); item 81 owns the dropped click.

## Proposal

1. **Keep what a failure produces.** Every job that runs Playwright specs gets an `if: failure()`
   upload of its `test-results/` (the trace, screenshot and `error-context.md` per failing spec),
   named per job and kept 7 days: `e2e`, `macos-e2e`, `macos-dmg`, `windows-desktop-zip`,
   `desktop-e2e`, `desktop-nix-e2e` and `desktop-bundle-e2e`.
2. **Lint without the devenv shell.** The two actionlint steps run the flake-pinned actionlint
   directly (from the pinned nixpkgs, the same version the devenv provides — ADR 0010) instead of
   inside `devenv shell`, so linting realizes actionlint only and never a release asset. The jobs'
   other steps keep the shell; an asset-host outage fails only the steps that fetch assets.
3. **docs-check survives the registry.** The job runs every check that needs no installed package
   (the four documentation checks, `check-exceptions`, and the `node --test` files that do not run
   `tsc`) before the install, and installs offline-first from the cached pnpm store; only the type
   check and the format check need the install.
4. **Current action majors.** New and existing `actions/upload-artifact` steps move to the current
   major, and the `windows-whisper-asset` job's remaining Node-20 action is updated, so a run carries
   no Node 20 deprecation annotation.
5. **Make every recorded flake deterministic:**
   - `13-second-quit`: capture the app's process handle before the quit, so the `finally` cannot
     read a closed channel, and keep failing on a real failure.
   - `ProcessRunnerSpec`: express "a pause issued while the process is being spawned" with a
     synchronization point instead of the 500 ms wall-clock head start.
   - `59-row-bars`: observe the `Diarizing…` text and the bar count in one observation (as item 65
     did for `59-row-color`), so the task finishing between two checks cannot fail it.
   - `52-keyboard-trial`: once part 1 keeps its trace, find why one Tab misses Edit on macOS and fix
     the spec — or the app, if its focus order is wrong on macOS.

Rejected alternatives:

- **Saving the Nix cache on failure**: breaks item 53's single-writer rule (see Evidence).
- **Splitting the devenv shell** for lint/test jobs: a larger `devenv.nix` change than the lint
  needs; part 2 removes the only check step that failed on the asset host.
- **Uploading test results on every run**: costs time and storage on green runs; no spec retries,
  so nothing a green run produced is needed.
- **Uploading the whole Gradle report tree**: the failure message already reaches the console
  (item 77); what is missing for e2e is the trace, which lives outside it.
- **Retrying specs until green in CI**: hides the flake and keeps the diagnosis impossible.

## Decided (consultation, 2026-10-24)

- **Scope** (the user): parts 1 and 2 plus every recorded CI flake — `13-second-quit`,
  `ProcessRunnerSpec`'s pause, `59-row-bars` and `52-keyboard-trial` on macOS. Their rows leave
  `docs/known-issues.md` with this approval.
- **Artifacts** (open question 1): uploaded on failure only, kept 7 days. No spec retries today.
- **Lint** (open question 2): actionlint runs without the devenv shell. Saving the cache on failure
  is rejected (item 53); splitting the shell is not needed for the lint.
- **docs-check**: both — the dependency-free checks run before the install, and the install is
  offline-first from the warm pnpm store.
- **Action majors**: new and existing upload steps move to the current major.
- **e2e stance** (ADR 0046): the ADR requires an e2e test for every app feature; this item delivers
  no app feature, so it is exempt. Its proof is CI behavior: deliberately failing runs that attach
  their artifacts, and the flaky specs passing repeatedly. If `52-keyboard-trial` shows the app at
  fault, that fix is covered by the same spec.

## Open questions

None. (Both settled 2026-10-24, see "Decided (consultation, 2026-10-24)".)

## Test list (approved 2026-10-24; behaviors only)

Part 1 — failure artifacts:

- [ ] A deliberately failing journey spec in `Linux (e2e)` attaches that spec's trace, screenshot
      and `error-context.md` to the run as an artifact named for the job, kept 7 days
- [ ] The same holds for every job that runs Playwright specs: `macOS (e2e)`, the packaged Linux
      jobs (`.deb`, flake package, bundle), the macOS DMG job and the Windows packaged zip/MSI job
- [ ] (must not break) A green run uploads no failure artifact

Part 2a — the Nix jobs' lint does not need github.com:

- [ ] The actionlint step works without realizing the pinned whisper-cpp/ffmpeg release assets: with
      GitHub release downloads unreachable, it still passes
- [ ] That actionlint is the version the devenv pins

Part 2b — `docs-check`:

- [ ] Every dependency-free check and its tests run before `pnpm install`: with the registry
      unreachable and a cold cache, their results are in the log and only the type/format step fails
- [ ] With the registry unreachable and a warm pnpm store, the job passes

Node 20:

- [ ] Every `upload-artifact` and `cache` step in `ci.yml` uses the current major; a run carries no
      Node 20 deprecation annotation
- [ ] (must not break) The manual asset jobs still publish their artifacts (DMG, whisper-cpp, ffmpeg)

Part 3 — the recorded CI flakes:

- [ ] `13-second-quit` passes when the app has exited before its `finally` runs, and still fails on a
      real failure
- [ ] `ProcessRunnerSpec` "a pause() issued while the process is being spawned" holds without the
      wall-clock head start: it passes on a loaded runner
- [ ] `59-row-bars` checks the `Diarizing…` text and the bar count in one observation, so the task
      finishing between two checks cannot fail it
- [ ] `52-keyboard-trial` on macOS: the failure's cause is identified from its trace and the spec
      passes on macOS repeatedly; if the app is at fault, the fix is a behavior change covered by
      the same spec
- [ ] (must not break) The Linux, macOS and Windows journeys and packaged specs stay green, with the
      same spec counts

## Acceptance criteria

- Every browser or desktop failure lands with its trace in the run, so a diagnosis needs no console
  paste from the owner.
- An unreachable github.com or npm registry cannot fail the lint or the documentation checks on a
  Markdown-only pull request (a cold pnpm cache still needs the registry for the type/format step).
- The four recorded CI flakes are deterministic, and their rows are gone from the backlog.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, after the CI triage of runs
  #212, #216, #223, #236, #239 and #246. Not yet discussed.
- 2026-10-24: item 54's review added the `docs-check` registry dependency to the Evidence, the
  Proposal and the test list (the user's decision). Still a draft.
- 2026-10-24 (consultation, the user): scope = parts 1 and 2 plus every recorded CI flake (including
  the macOS `52-keyboard-trial` failure on PR #66); failure-only uploads kept 7 days; actionlint
  without the devenv shell; docs-check both checks-first and offline-first; current action majors
  for new and existing steps; exempt from ADR 0046 (no app feature). Test list approved as worded.
  The flake rows (`13-second-quit`, `ProcessRunnerSpec`, `59-row-bars`) left
  `docs/known-issues.md` in the same commit. Status: spec-approved.
