# 82 — A red CI job is diagnosable from CI alone, and a check job does not fail for the network

Status: done (roadmap item 82, approved 2026-10-25)
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
- **A stale nixpkgs changes what the lint reports.** Dispatched on a tree whose `devenv.lock`
  pointed at nixos-24.05, the lint ran actionlint 1.7.0 and rejected the workflow itself — `label
  "ubuntu-24.04" is unknown` on every step and the `schedule` entry as invalid — while the pinned
  1.7.12 accepts it. So the lock, not the job, decides both the rules actionlint applies and whether
  the lint passes; a step that ran whatever the PATH held would report failures devenv never sees.
- **`pnpm --dir docs test` installs on its own when `node_modules` is missing.** In a cold store
  the job's last step starts its own dependency install, so a registry outage fails it there
  (`✗ Lockfile failed supply-chain policy check`, `ERR_PNPM_META_FETCH_FAIL`) after the
  dependency-free step has already printed its results — the step is still the type/format one,
  but a red run spends about four more minutes in the retries.
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
    seen again in run 40156465546 (job `Linux (packaged .deb e2e)`, 9 passed, 5 skipped, 1 failed):
    the assertions passed, then the `finally` threw the same `TypeError`, and this time the failure
    upload kept the trace and `error-context.md` (`desktop-test-results-linux-deb`, artifact
    12585106798, 10,520 bytes, until 2026-11-01);
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
    `macOS (e2e)` on PR #66, merge ref `b9feb0d`, 51 of 52 specs passed). No trace was kept then;
    reproduced in run 40161156268 (`macOS (e2e)`, commit `e58494e3`, 51 of 52 specs passed) with
    part 1's upload: artifact `e2e-test-results-macos` 12586975492 (1,785,229 bytes, kept until
    2026-11-01) holds the trace, screenshot and `error-context.md`. Whether `macOS (e2e)`'s
    failure in run 40099483165 was the same spec is unknown.
  - `52-keyboard-trial` on Linux, at another step (found by part 1's upload, 2026-10-24): after
    Space started playback from the staged segment, the second Space did not pause it —
    `expect.poll(playing).toBe(false)` received `true` for the whole 20 s
    (`e2e/tests/52-keyboard-trial.spec.ts:44-45`; job `Linux (e2e)` of run 40119228781 on
    `6dcfd632`, artifact `e2e-test-results-linux` 12570905948 with the trace, kept until
    2026-10-31). The page snapshot shows the player's `Pause` button and `00:19 / 01:39`. The spec
    passed in `Linux (e2e)` of runs 40110339285 and 40111711324.
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

- [x] A deliberately failing journey spec in `Linux (e2e)` attaches that spec's trace, screenshot
      and `error-context.md` to the run as an artifact named for the job, kept 7 days
- [x] The same holds for every job that runs Playwright specs: `macOS (e2e)`, the packaged Linux
      jobs (`.deb`, flake package, bundle), the macOS DMG job and the Windows packaged zip/MSI job
- [x] (must not break) A green run uploads no failure artifact
- [x] (discovered) A failing desktop spec's trace records the app window's actions, DOM snapshots
      and screencast, as a failing journey spec's does — not only the test's steps
- [x] (discovered) Closing a desktop app that has already exited does not throw, and its trace is
      not stopped (nothing left to save)

Part 2a — the Nix jobs' lint does not need github.com:

- [x] The actionlint step works without realizing the pinned whisper-cpp/ffmpeg release assets: with
      GitHub release downloads unreachable, it still passes
- [x] That actionlint is the version the devenv pins

Part 2b — `docs-check`:

- [x] Every dependency-free check and its tests run before `pnpm install`: with the registry
      unreachable and a cold cache, their results are in the log and only the type/format step fails
- [x] With the registry unreachable and a warm pnpm store, the job passes

Node 20:

- [x] Every `upload-artifact` and `cache` step in `ci.yml` uses the current major; a run carries no
      Node 20 deprecation annotation
- [x] (must not break) The manual asset jobs still publish their artifacts (DMG, whisper-cpp, ffmpeg)
- [x] (discovered) The action-versions check reads a `uses:` line's whole ref, so a floating ref
      (`@main`) fails and a commented `uses:` line is ignored

Part 3 — the recorded CI flakes:

- [x] `13-second-quit` passes when the app has exited before its `finally` runs, and still fails on a
      real failure
- [x] `ProcessRunnerSpec` "a pause() issued while the process is being spawned" holds without the
      wall-clock head start: it passes on a loaded runner
- [x] `59-row-bars` checks the `Diarizing…` text and the bar count in one observation, so the task
      finishing between two checks cannot fail it
- [x] `52-keyboard-trial` on macOS: the failure's cause is identified from its trace and the spec
      passes on macOS repeatedly; if the app is at fault, the fix is a behavior change covered by
      the same spec
- [x] (discovered) `52-keyboard-trial` on Linux: the cause of the second Space not pausing is
      identified from its trace and the spec passes on Linux repeatedly; if the app is at fault, the
      fix is a behavior change covered by the same spec
- [x] (must not break) The Linux, macOS and Windows journeys and packaged specs stay green, with the
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
- 2026-10-24 (workspace, test-list item 1): red run 40110339285 (dispatch on the temporary failing
  spec `e2e/tests/00-deliberate-failure.spec.ts`): `Linux (e2e)` failed on that spec alone and the
  run kept no `Linux (e2e)` artifact. Green run 40111711324 (`Test:` commit `0fa230e2`): the
  failure-only upload attached `e2e-test-results-linux` (813,979 bytes; expires 2026-10-31,
  7 days). The downloaded artifact holds exactly the failing spec's directory: a 781 KB
  `trace.zip` with the test, action and network traces and the screencast, a 45 KB PNG
  `test-failed-1.png`, and `error-context.md` with the error and the page's ARIA snapshot. No
  refactor (the user). The temporary spec stays through test-list item 2 and is reverted before
  item 3 (the user).
- 2026-10-24 (workspace, test-list item 2): red run 40113332253 (dispatch on `520c7167`, the
  temporary desktop spec `e2e/desktop-tests/00-deliberate-failure-01-journey.spec.ts` beside the
  journey one): `macOS (e2e)`, `macOS (DMG + packaged e2e)`, `Windows (packaged zip e2e)` and the
  `.deb`, flake package and bundle jobs each failed on a deliberate spec alone, listed its trace,
  screenshot and `error-context.md` under `test-results/`, and uploaded none. Green run
  40114792470 (`Test:` commit `cd2c879d`): each failing job attached its artifact, kept 7 days
  (expires 2026-10-31): `e2e-test-results-linux` (828,215 bytes), `e2e-test-results-macos`
  (217,417), `desktop-test-results-macos-dmg` (82,125), `desktop-test-results-windows` (78,206),
  `desktop-test-results-linux-deb` (90,985), `desktop-test-results-linux-flake` (73,604),
  `desktop-test-results-linux-bundle` (74,035). The downloaded bundle artifact holds exactly the
  failing spec's directory: `trace.zip`, a 35 KB PNG `test-failed-1.png`, and `error-context.md`
  with the error and the app window's ARIA snapshot. Its `trace.zip` has 4 entries — the test's
  steps (`Launch electron`, `Expect "toBeVisible"`, `Close context`) and the two attachments — and
  none of the journey trace's action, network or screencast records: Playwright's
  `retain-on-failure` traces only the contexts its fixtures create, not the one
  `_electron.launch` opens (`e2e/desktop-tests/helpers/app.ts:73`). Added as a (discovered) item.
  Refactor (the user): the seven upload steps call one composite action,
  `.github/actions/upload-test-results` (`c4a0a36c`).
- 2026-10-24 (workspace, discovered desktop-trace item, confirmed by the user): red = run
  40114792470's bundle artifact above. Green run 40119228781 (`Test:` commit `6dcfd632`;
  `launchDesktopApp` traces the app's context and `close` saves `app-trace-<n>.zip`, the desktop
  config keeps a failing spec's output only): `desktop-test-results-windows` (1,926,170 bytes)
  holds exactly the deliberate spec's directory — its `app-trace-1.zip` (928 KB, 52 entries) with
  both `expect` actions, 4 DOM snapshots, 45 screencast frames, network and page resources, beside
  Playwright's `trace.zip`, `test-failed-1.png` and `error-context.md`; the step's 4 passing specs
  left nothing. The `.deb`, flake and DMG artifacts grew the same way (209,981, 2,209,052 and
  401,846 bytes). The uploads ran through the composite action. No refactor (the user). The same
  run's `e2e-test-results-linux` (artifact 12570905948, 3,554,700 bytes) also holds a real
  failure of `52-keyboard-trial` in `Linux (e2e)`, with its trace: the second Space left the audio
  playing (`e2e/tests/52-keyboard-trial.spec.ts:45`, 20 s) — on Linux, and at a different step
  from PR #66's macOS failure (`:56-57`). Kept for the part-3 item.
- 2026-10-24 (workspace, test-list item 3): the two temporary specs reverted (`eca22e73`,
  `24d9f7fe`; the suites list 52 journey and 15 desktop specs again). Green run 40120646508
  (dispatch on `24d9f7fe`): every job that ran passed, `CI (all checks)` included, and the run's
  one artifact is the DMG (`Speech-to-Text-macos-arm64-dmg`) — no failure upload. No refactor (the
  user). The discovered `52-keyboard-trial` Linux item is confirmed (the user). Part 1 done.
- 2026-10-24 (workspace, test-list item 4, part 2a): red run 40122768196 (dispatch on the temporary
  `0f1e6478`, which installed Nix without the store cache in both Nix jobs, mapped `github.com` to
  `0.0.0.0` and verified it unreachable): `actionlint the workflows` failed in both before
  actionlint ran — `devenv shell` could not build the shell. `Linux (Nix devenv shell)` failed on
  `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` from `github.com/k2-fsa/sherpa-onnx/releases`,
  `macOS (Nix build + devenv)` on `ggml-large-v3-turbo-q5_0.bin` from this repository's
  `desktop-models-v1` release (`curl: (7) Failed to connect to github.com:443`, four attempts each,
  then `Failed to realize shell derivation`). The shell's pinned models broke before whisper-cpp and
  ffmpeg; both are release downloads, and the nixpkgs and devenv tarballs still came through
  `api.github.com`. Green run 40123825420 (`Test:` commit `52aab6cf`; the same outage and cold
  store): `actionlint the workflows` passed in both jobs, realizing only the nixpkgs source and
  `actionlint-1.7.12` from cache.nixos.org — the Linux job's `/nix/store/bwxcfsghzk4hyynxgl3lvjgmcnkc7qhf`,
  the same path as devenv's actionlint — and `devenv test` then failed in both, as it must. The
  step now runs `nix run --inputs-from . nixpkgs#actionlint`. Refactor question: no refactor (the
  user), recorded in the rework note below. The temporary outage commit is reverted (`5e85863a`)
  before review.
- 2026-10-24 (workspace, test-list item 5, part 2a): red run 40125997707 (dispatch on the temporary
  `cbebb6d8`, whose `devenv.lock` named the real nixos-24.05 rev `b134951a`): `Linux (Nix flake
  check)` failed in `devenv-pin-sync` — `devenv.lock evaluates nixpkgs b134951a… but flake.lock has
  494ce7fd…bf25` — and `Linux (Nix devenv shell)` passed the new lint step, then failed the version
  step: `the lint runs actionlint 1.7.12 … but devenv.lock pins nixpkgs b134951a… with 1.7.0`. The
  run also exposed a defect in the step itself: actionlint's `--version` prints three lines
  (`1.7.12`, `installed by building from source`, the Go compiler line), so the comparison now takes
  `head -n1`. Green run 40126650750 (`Test:` commit `803917fa`): every job passed, `CI (all checks)`
  included, and the step printed `actionlint 1.7.12 is the version devenv.lock pins (nixpkgs
  494ce7fd…)`; `devenv-pin-sync` passed in `Linux (Nix flake check)`. The step reads `devenv.lock`
  (what the CLI evaluates) and `devenv-pin-sync` now also requires it to name flake.lock's nixpkgs.
  The temporary drift is reverted (`a50560a9`) before review. Refactor question: approved (the
  user), commit `4abf8efe` ("Refactor: name the actionlint version step as the check it makes, not
  a claim"; `29f96d47` before the rebase), recorded in the rework note below.

- 2026-10-24 (workspace, test-list item 6, part 2b): red run 40149761465 (dispatch on the temporary
  `c96d5ab1`: the old job order plus `registry.npmjs.org` mapped to `0.0.0.0`/`::1` and an empty
  `$RUNNER_TEMP/cold-pnpm-store`, install `--store-dir`ed there): `pnpm install` retried
  `ECONNREFUSED` for 3m 30s, then `✗ Lockfile failed supply-chain policy check` /
  `ERR_PNPM_META_FETCH_FAIL`, exit 1 — no check ran. An earlier red (`40149666859`) had emptied
  `pnpm store path` and deleted pnpm's self-installation (`Cannot find module …/pnpm/bin/pnpm.mjs`),
  so it was reverted as invalid (`git revert`-style, `5c1ca0bb`/the TEMP revert chain). Green run
  40152444487 (`Test:` commit `8358315c`, cherry-picked back after its own revert, plus the same
  outage as `9839b998`): `The dependency-free checks` printed `ℹ tests 19 / ℹ pass 19 / ℹ fail 0`,
  install failed on `ECONNREFUSED` and was tolerated, and only `pnpm test` failed (its own implicit
  install, then `ERR_PNPM_META_FETCH_FAIL`) — the checks' results stayed in the log. The job is red
  under the outage, as the item says; part 2b's next item proves the warm-store path green. The
  outage is reverted (`d52af8b7`) before review. No refactor (the user).
- 2026-10-25 (workspace, test-list item 7, part 2b): green run 40156465546 (dispatch on the
  temporary `10f0f9d3`, which maps `registry.npmjs.org` to `0.0.0.0`/`::1` after the node-toolchain
  action restored the docs pnpm store cache): `Linux (documentation checks)` passed every step —
  `Make the npm registry unreachable`, `The dependency-free checks`, `pnpm install` (the frozen
  lockfile with `--config.trustLockfile=true`) and `pnpm test` — with no registry, so a
  documentation-only pull request no longer reddens on an npm outage when the store is warm. The
  run's overall failure is the recorded `13-second-quit` flake, now with a trace: the `.deb` job ran
  its 15 desktop specs (9 passed, 5 skipped, 1 failed) and
  `13-second-quit-during-shutdown.spec.ts:33` threw `TypeError: Cannot read properties of undefined
  (reading '_object')` after its assertions passed; the failure upload attached
  `desktop-test-results-linux-deb` (12585106798, 10,520 bytes, trace + `error-context.md`, expires
  2026-11-01), the first diagnosis material for part 3. The run also annotates `macOS (DMG +
  packaged e2e)` with the Node 20 deprecation (`actions/upload-artifact@v4`: the DMG upload and the
  four manual asset jobs), the red for the next item. The outage commit is reverted
  (`c4effb3d`) before review. No refactoring (the user).
- 2026-10-25 (workspace, test-list item 8, Node 20): red = run 40156465546's warning on `macOS (DMG
  + packaged e2e)` (`actions/upload-artifact@v4`) and `node --test
  docs/check-action-versions.test.ts` failing on `.github/workflows/ci.yml:425` (`upload-artifact@v4
  is older than @v7`). Green (`Test:` commit `a2015a14`): the five `upload-artifact` steps — the DMG
  and the four manual assets — are `@v7`; the new check scans every workflow and composite action
  under `.github/` and requires `upload-artifact` at v7 or later and `cache` at v6 or later, runs in
  `pnpm --dir docs test` and in the dependency-free CI step, and is documented in `AGENTS.md`. The
  sandbox cannot run `pnpm --dir docs test` itself (no `/bin/sh` or `/usr/bin/env` for pnpm's
  shims), so its steps were run directly: tsc, Prettier, the five checks and the six test files (21
  tests) all passed. Green run 40158389228 (dispatch on `e051e6c7`): all 22 executed jobs succeeded,
  `CI (all checks)` included, and the run's only annotations are the macOS runner-capacity notices
  — no Node 20 deprecation; `Speech-to-Text-macos-arm64-dmg` (261,808,247 bytes) is attached, so the
  DMG upload at v7 still publishes. `Linux (packaged .deb e2e)` passed, so the `13-second-quit`
  flake did not recur. No refactoring (the user).
- 2026-10-25 (workspace, test-list item 9, Node 20 must not break): run 40159405333 (dispatch on
  `e0ca67f8` with `assets: true`): every job succeeded, `CI (all checks)` included, with only the
  weekly model-host probe skipped. Each of the four manual jobs finished
  `Run actions/upload-artifact@v7`, and the run attached five artifacts: the DMG
  (`Speech-to-Text-macos-arm64-dmg`, 261,808,483 bytes, until 2026-11-24) and the four assets kept
  90 days until 2027-01-23 — `whisper-cpp-1.8.4-windows-x64` (35,449,089 bytes),
  `ffmpeg-audio-only-8.1.2-windows-x64` (2,070,779), `ffmpeg-audio-only-aarch64-darwin` (1,826,094)
  `whisper-cpp-1.8.4-aarch64-darwin` (1,142,449). No refactoring suggested: the item changed no
  code, the dispatch is its proof.
- 2026-10-25 (workspace, test-list item 10, part 3, `13-second-quit`): red = run 40156465546's
  `.deb` failure — the `finally` read `app.process()` after the app exited and threw the
  `TypeError`, masking the passing assertions (trace in `desktop-test-results-linux-deb`
  12585106798). Green (`Test:` commit `e58494e3`): the spec takes `app.process()` once while the app
  runs, and the `finally` reads `exitCode`/`signalCode` from that handle; the only post-exit
  `app.process()` call is gone. Run 40161156268 ran the spec in the `.deb`, flake-package, DMG and
  Windows-zip jobs and all four passed (that run's red is the recorded macOS `52-keyboard-trial`
  above). Real-failure leg: run 40162708047 (dispatch on `afe029b8`, which carries the TEMP
  `98df5ada` making the app quit without waiting for the backend) — the `.deb` job failed only
  `13-second-quit` (9 passed, 5 skipped, 1 failed) with `Error: nothing the app owns outlives it`,
  receiving the surviving JVM `6320: /opt/Speech-to-Text/resources/jre/bin/java … stt.MainKt` at
  `:37`; the `finally` then read the captured handle at `:39` without throwing, so the real
  assertion is what fails. The upload kept its trace and `error-context.md`
  (`desktop-test-results-linux-deb` 12587371819, 11,548 bytes). The plant is reverted (`c1833769`)
  before review. No refactoring (the user).
- 2026-10-25 (workspace, test-list item 11, part 3, `ProcessRunnerSpec`): red = run 40041313804's
  macOS integrationTest (`List should be empty but has 3 elements, first being: "1"`) — on a loaded
  runner the pause landed after the loop's 500 ms head start. Green (`Test:` commit `df3f7066`):
  the child prints nothing until a gate file exists, and the test creates that file only after
  `awaitFrozen()` has seen every process of the run tree stopped (`T`, or `Z` for a zombie), so no
  line can precede the SIGSTOP; `pausedFromSpawn` takes a `beforeResume` hook and `countingLoop`
  lost its head-start parameter. `gradle ktlintCheck compileTestKotlin integrationTest --tests
  'stt.process.ProcessRunnerSpec'` passed, then three `--rerun` passes under eight `yes` CPU loops
  (15/13/14 s). The macOS `ps` state path runs in the next dispatch's integrationTest jobs. No
  refactoring (the user).
- 2026-10-25 (workspace, test-list item 12, part 3, `59-row-bars`): red = run 39978912415's
  journey e2e — the `Diarizing… N%` text was visible, then the task finished and its row left the
  queue before the separate `toHaveCount(2)` check could see the second bar (20 s timeout).
  Green (`Test:` commit `7bc7c538`): before the upload the spec starts a page-side
  `MutationObserver`, and every DOM change samples the pending row once — its phase span, the
  number of its `[role=progressbar]` elements and their `Transcription`/`Speaker diarization`
  names in the same page-side turn; after the row leaves, the test asserts from the recorded
  samples that one pre-diarizing observation has exactly the transcription bar and one
  `Diarizing…` observation has both bars, so no task end can fall between two checks. Run
  40165015996 on `7bc7c538`: Linux (e2e) success, so the spec passed there; macOS (e2e) failed
  only the recorded `52-keyboard-trial` — its uploaded artifact holds exactly one failed spec,
  `52-keyboard-trial-the-keyb-560ed-script-Tab-Space-and-Escape`, with its `error-context.md` and
  trace — so `59-row-bars` passed on macOS too, and both integrationTest jobs passed, which also
  closes item 11's macOS `ps` path. Locally only `tsc --noEmit` and Prettier over `e2e/` ran: the
  downloaded Chrome cannot launch in the sandbox (no `/lib64/ld-linux-x86-64.so.2`), so the
  journey runs are the proof. No refactoring (the user).
- 2026-10-25 (workspace, test-list item 13, part 3, `52-keyboard-trial` on macOS): red = run
  40165015996's macOS (e2e) — 51 passed, 1 failed: the poll at `52-keyboard-trial.spec.ts:57`
  expected `ariaLabel: 'Edit segment'` and received `null` for 20 s; the uploaded artifact
  (`e2e-test-results-macos` 12587728345, 1,881,755 bytes) holds exactly that spec's
  `error-context.md` and trace. Cause from the trace: Space paused at 598893 and the poll saw
  `audio.paused` at 598898, but the Tab at 598923 ran while the playback state still said
  playing — the current segment's controls render only when idle — so it skipped the
  not-yet-mounted Edit control and moved to the next focusable, the next segment's text button
  (no `aria-label`, the poll's `null`). Screencast frames: +39 ms after the Tab the player still
  showed Pause and the current segment had no controls; +150 ms later it showed Play with the
  Edit and Play-segment controls present. The playback state followed the element's `pause`
  event, which landed a frame after the command. Fix (`45aee735`): a pause/stop command reports
  idle at once in `togglePlayback`, `stopAt` and `stopPlaying`; a play still waits for the
  element's `play` event (autoplay can refuse), and the machine's comment says so. Local: the
  frontend 225/225 tests, `tsc --noEmit` and Prettier; a temporary assertion found the Edit
  control absent right after Stop without a `pause` event and present with the fix (reverted,
  not committed). macOS: run 40211158986 on `45aee735` — attempt 1 (dispatch, 27 jobs), attempt 2
  (Re-run all jobs, 27 jobs) and attempt 3 (27 jobs) all green, with `macOS (e2e)` green in each,
  so the spec passed three macOS executions of the same tree. Refactoring approved (the user):
  one pause-and-idle helper.
- 2026-10-25 (workspace, test-list item 14, part 3, `52-keyboard-trial` on Linux): red = run
  40119228781's Linux (e2e) — after Space started playback from the staged segment, the second
  Space did not pause it (`expect.poll(playing).toBe(false)` received true for 20 s; artifact
  `e2e-test-results-linux` 12570905948). The cause is in the trace's recorded evaluate results:
  before the Tab the focus was the current segment; the Tab landed on its Edit control while the
  playback state still said idle, and the element's `play` event then made it playing, so the
  controls unmounted under the focus and it fell to the body (the next evaluate returns the whole
  page); both Spaces then hit the body and the audio played on from 0 (the failure snapshot shows
  00:19). An optimistic app state was tried and reverted (`ded33334`, `ebebeb68`): in Chrome every
  Play left the app idle — six specs red on Linux and macOS in run 40219747549 — so the
  element-driven state (item 25) stays. The spec now waits for the player's Pause, the state the
  controls' tab order follows, before its Tab (`979bb7f4`). Green: run 40221209191 on `979bb7f4` —
  attempt 1 (dispatch, 27 jobs), attempt 2 and attempt 3 (Linux (e2e) re-runs) all green, so the
  spec passed three Linux executions of the same tree; that run's `macOS (e2e)` was green too (the
  macOS item's three runs were on `45aee735`, whose app code this tree keeps apart from the
  behavior-preserving pause helper refactor). No refactoring (the user).
- 2026-10-25 (workspace, test-list item 15, must not break): run 40221209191 on `979bb7f4` (the
  code this workflow's record commits do not change) — attempt 1, all 27 jobs green. The journeys
  kept their counts: Linux (e2e) `Running 52 tests`, 1 skipped (the `18-macos-metal` skip), 51
  passed; macOS (e2e) `Running 52 tests`, 52 passed. The packaged jobs (Linux bundle, flake package
  and `.deb`; macOS DMG; Windows zip) are green over the desktop suite — the same 15 spec files
  under `e2e/desktop-tests`, and the same 52 journey/15 desktop counts recorded for run 40120646508.
  The user approved this run as the item's evidence. No refactoring suggested: the cycle added no
  code.
- 2026-10-25 (workspace, rework, root review finding, discovered item): red = the new
  `e2e/desktop-tests/helpers/app.test.ts` run before the fix — `Error: This ElectronApplication
  has been closed`, thrown by the fake app's `process()` after the exit, from the close wrapper's
  `app.process()` at `app.ts:105`; the same call threw in runs 39540019816, 39891003455 and
  40156465546. Green (`Test:` commit `af49d5d9`): `recordAppTrace` takes `app.process()` before
  `tracing.start`, and the close wrapper reads that captured handle's `exitCode`/`signalCode`, so
  an app that exited before its `finally` stops no trace and closes without an error; the doc
  comment now names the runs and the captured handle. The test runs in the package's `node --test`
  list (`5/5`); `check-playwright.ts`, Prettier and `tsc -p tsconfig.json` green. The desktop
  specs' close path is covered by the PR's CI before merge; this rework did not run the full
  workflow. No refactoring (the user).
- 2026-10-25 (workspace, rework, root review bookkeeping finding): the two pending refactor lines
  above are answered — item 4's checkpoint: no refactor (the user); item 5's refactor: commit
  `4abf8efe`, "Refactor: name the actionlint version step as the check it makes, not a claim" (was
  `29f96d47` before the rebase), approved (the user).
- 2026-10-25 (workspace, rework, root review finding, discovered item): red = the new focused test
  against the old matching — the commented `# uses: actions/cache@v4` was counted as a step and
  `actions/upload-artifact@main` was not seen (`{cache: 2, upload-artifact: 1}` against the
  expected `{1, 2}`). Green (`Test:` commit `279a5ad3`): the scan reads the whole ref after `@`,
  skips a line whose first non-space character is `#`, and fails a ref that is not a pinned
  `v<digits>` ("is not a pinned major (@v7 or later)") while keeping the older-major message; the
  header comment says so. Docs checks green: `tsc -p docs/tsconfig.json`, Prettier, the five
  checks and the six `node --test` files (22 tests). No refactoring (the user).
- 2026-10-25 (root PR preparation, approved by the user): the branch was rebased onto `main` at
  `d39e75ed` (item 81) before the rework. The close-wrapper unit test moved from
  `e2e/desktop-tests/helpers/app.test.ts` to `e2e/lib/app-trace.test.ts`, and the desktop Playwright
  config matches `**/*.spec.ts`, so `playwright --list` no longer loads or runs that test in the
  packaged jobs. The item is marked done; this spec and its final test list moved to
  `docs/specs/ci-diagnosability.md`, the roadmap copy is deleted, and `roadmap/README.md` and item
  78 point at the spec. The earlier run 40221209191 and its 52/15 counts predate item 81; the PR's
  CI on the squashed branch is the final gate, and the user runs it before merge. Local checks:
  e2e 5/5, docs 22, frontend 233, backend `ktlintCheck` plus 24 runner tests.
