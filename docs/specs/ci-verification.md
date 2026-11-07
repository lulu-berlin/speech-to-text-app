# 51 — CI verification: first GitHub Actions workflow

Status: done (roadmap item 51, merged 2026-10-14)
Depends on: — (infrastructure; nothing to build on)
Blocks: — [48](macos-support.md) and [49](windows-support.md) (their CI-runner verification)

## Goal

The repository gets its first continuous-verification workflow. It runs the whole check stack
(`gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build`, the e2e journey) with
dependency caching so runs stay fast and deterministic. Linux and macOS run on every push and pull
request; the Windows legs keep a lower cadence (nightly) until item 49 lands. macOS verification
includes the Nix flake build; Windows has no Nix (Nix does not support Windows). Filed because the
macOS and Windows items were approved with CI as their verification path: nobody on the team owns
a Mac or a Windows machine, and the repo has no CI today.

## Evidence

- No `.github/workflows` exists in the repository (verified by listing; the root has only devenv/flake
  config).
- The standard commands are defined in AGENTS.md § Commands (ready-made for a runner: `gradle check`,
  `gradle integrationTest` needs `setsid` from util-linux — present on ubuntu runners; `pnpm
  test`/`build`; the top-level e2e package) and the devenv shell pins every tool version.
- e2e and integration tests need real binaries and models: whisper-cli, ffmpeg, the CAM++ ONNX
  model, the whisper model (~1.5 GB) — heavy downloads that a workflow must cache to be usable.
- Item 48's Nix-darwin verification ("`nix build`/`nix run` and the devenv shell work on darwin")
  has no local Mac to run on; a macOS runner with Nix is the automated home for it.

## Decided (consultation, 2026-10-13)

- **Matrix order**: a Linux-first workflow now; the macOS (ARM; Intel per item 48) and Windows matrix
  legs are added as 48/49 land.
- **Cadence**: every push (to `main` and PRs): Linux unit + format + pnpm + integration + e2e, and
  (amended 2026-10-21, item 48) the full macOS legs on every push and pull request. Lower cadence
  (nightly scheduled runs) for the Windows legs; item 49's PRs can trigger their own leg on demand.
  (amended 2026-10-30) The Windows journey (`Windows (e2e)`) runs and reports on every code change;
  (amended 2026-10-31, roadmap item 84) it is back in the aggregate `CI (all checks)` after five
  consecutive green journeys — see [windows-e2e-journey.md](windows-e2e-journey.md).
- **Nix**: macOS CI legs run the flake build (`nix build`/`nix run` and the devenv shell) — that is
  item 48's Nix-darwin verification. No Nix job on Windows (Nix has no Windows support). Linux CI is
  not required to use Nix (the Gradle/pnpm/bootstrap path is sufficient and matches ADR 0010's
  user-verified Nix split for Linux).

## Decided (first test item, 2026-10-14)

- **Triggers**: push to `main` and `pull_request`, plus `workflow_dispatch`. A pushed branch
  without a PR gets no run; every triggered run runs the whole Linux tree (fast gate, then
  integration and e2e) and, since item 48, the macOS tree.
- **Nightly**: `schedule` at 03:17 Europe/Berlin (the `timezone:` field; moved off minute :00, the
  busiest one for scheduled runs, in roadmap item 79). It runs the Linux and
  macOS trees (the Windows legs follow with item 49), so the trigger itself is exercised.
- **macOS/Windows legs belong to 48/49**: each platform item adds its own legs to the workflow as
  part of its work — item 48 runs macOS on every push and pull request (amended 2026-10-21), item 49
  keeps the nightly cadence and its own PRs. The former test items "nightly macOS/Windows legs pass"
  and "Nix on the macOS runner, none on Windows" moved to items 48 and 49.
- **Linux Nix job** (user decision, over the earlier "Linux CI is not required to use Nix"): CI runs
  the devenv shell, `nix flake check` and `nix build` on Linux, with a Nix store cache so it stays
  fast. No secrets, so the cache cannot be a push-token binary cache.
- **Repo is public**: standard GitHub-hosted runners are free; the 10 GB per-repo Actions cache is
  the size limit for the caches.

## Proposal

1. A single workflow, `.github/workflows/ci.yml`: a Linux job tree (fast gate `gradle check` +
   `pnpm test` + `pnpm build`, then integration and e2e), a Linux Nix job, and the nightly schedule
   that 48/49 extend with their platform legs.
2. Caching: Gradle (`~/.gradle`), pnpm (`pnpm store`), the model/tool downloads (the bootstrap
   already hashes them), and the Nix store, so no multi-GB re-download on any run.
3. Secrets: none (no deployment yet). Report trend flakiness to the known-issues backlog per its
   Maintenance rules.

## Test list (approved 2026-10-14 with the spec; all items green except the nightly trigger)

- [x] A pushed commit (to `main` or a PR) runs the workflow and the Linux fast gate passes
      `gradle check` + `pnpm test` + `pnpm build` with warm caches in a sensible time
- [x] The Linux integration and e2e jobs pass on every run (with `setsid` available)
- [x] Caches hit on the second run (no multi-GB re-download of models/tools)
- [x] (discovered) The Linux Nix job passes the devenv shell, `nix flake check` and `nix build`, and
      a warm run reuses the cached store instead of rebuilding
- [ ] (discovered) The nightly schedule runs the workflow at 03:17 Europe/Berlin — deferred to after
      the merge (chosen with the user, 2026-10-14): GitHub runs a scheduled workflow only from the
      default branch, so the first real nightly run is observed once this branch is on `main`
- [x] A flaky CI failure is reported to the known-issues backlog, not silently tolerated

## Acceptance criteria

- The repo has a working, cached, documented CI workflow exercising the standard commands and the
  Nix build on Linux, on `main` pushes and PRs, plus a nightly schedule that items 48/49 extend with
  their platform legs. Every new roadmap item's acceptance tests run somewhere automated.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed as the verification vehicle the macOS/Windows consultations asked for ("I don't
  have a Mac", "maybe we can use CI").
- 2026-10-13: consultation — Linux-only matrix first until 48/49 land; e2e/integration on every push
  for Linux, lower (nightly) cadence for macOS/Windows; Nix builds in CI on macOS, no Nix on
  Windows. Status: spec-approved.
- 2026-10-14 (workspace, first test item): open questions settled with the user. Triggers: push to
  `main` + `pull_request` (+ dispatch), all Linux jobs on each run; nightly 03:00 Europe/Berlin;
  48/49 own their platform legs (test items 4 and 5 moved there); a Linux Nix job (devenv shell,
  `nix flake check`, `nix build`) with a fast store cache added as a discovered item; nightly trigger
  added as a discovered item; repo is public. Next: test item 1 (fast gate), confirmed by the user.
- 2026-10-14 (workspace, test item 6): a real flaky failure was filed instead of tolerated —
  run #1 attempt 1, job `116006313314`: `gradle check` died in the configuration phase with
  `429 Too Many Requests` from `repo.maven.apache.org` resolving
  `kotlin-gradle-plugins-bom:2.3.21`; the same commit passed on re-run (attempt 2). Recorded in
  `docs/known-issues.md` § Build, packaging, docs. Item 6 ticked (behavioral: the report was made).
  Also found while classifying it: `gradle/actions/setup-gradle@v6` defaults `cache-read-only` to
  true off the default branch, so PR runs never write the Gradle cache — the workflow now sets it
  false. Item 1's "warm caches" and item 3 need a cold+warm pair of runs after that change.
- 2026-10-14 (workspace, test item 2 build; NOT yet verified): the integration and e2e legs reuse
  item 33's root bootstrap instead of duplicating the cache-path logic — a new root `integrationTest`
  task (`prepareRuntime` + `prepareE2eFixtures`, app + fixture environment, then
  `./gradlew -p backend integrationTest`) and two CI jobs (`backend-integration`; `e2e` with
  `pnpm --dir e2e install`, `sudo … playwright install-deps chromium`, `./gradlew e2e`), each caching
  `~/.gradle/stt-app` keyed on `versions.json`. Locally verified: `ktlintCheck` green,
  `integrationTest --dry-run` graph correct, `gradle -p backend integrationTest` green with the devenv
  environment (336 tests, 0 failures). Not verifiable here: the task's own environment plumbing and
  the nested wrapper (no `/bin/sh`, empty download cache) — item 2 ticks only on a reported real run.
- 2026-10-14 (workspace, test items 4 and 5 builds; NOT yet verified): the Linux Nix job uses
  `cachix/install-nix-action@v31` + `nix-community/cache-nix-action@v7` — the user's choice over
  Determinate's magic-nix-cache, whose default FlakeHub path wants `id-token: write` — caching `/nix`
  keyed on `flake.lock`, `devenv.lock`/`devenv.yaml`/`devenv.nix`, `nix/**` and `versions.json`, with
  a 6 GB gc cap inside the 10 GB repo budget. It installs the devenv CLI from nixpkgs (the one
  unpinned bootstrap tool, ADR 0010) and runs `devenv test`, `nix flake check` and `nix build`. The
  nightly trigger is `schedule` with `cron '0 3 * * *'` and a per-cron `timezone: Europe/Berlin`
  (syntax confirmed in the workflow-syntax docs). Neither is verifiable here: the Nix job needs a
  runner plus a warm second run for the store reuse, and a scheduled workflow only runs from the
  default branch — item 5 can only be ticked after this branch is merged.
- 2026-10-14 (workspace, test items 1–4 ticked): run [38624698425](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/38624698425)
  green on `394de0b1` with all five jobs starting in the same second — backend-check 2:00 and
  frontend 0:56 (Gradle `gradle-dependencies-v2-*`/`wrapper-zips`/`gradle-home-v2|…` and both pnpm
  caches restored), backend-integration 3:15, e2e 8:47, nix 1:49 (the 3.47 GB `nix-Linux-…` store
  cache restored; `nix build` took 2 s), and the 1.0 GB `stt-downloads-Linux-…` entry accessed at
  19:34:38, so no model or tool was re-downloaded. Item 5 still needs the merge (a scheduled workflow
  runs only from the default branch).
- 2026-10-14 (workspace, in-review, user decision): items 1–4 and 6 are ticked and green; item 5 (the
  nightly trigger) cannot be observed before the merge, so the user chose to set `Status: in-review`
  now and confirm the first nightly run from `main` after merging. Also implemented at the user's
  request: the three Nix checks are three parallel jobs (`nix-devenv`, `nix-flake-check`, `nix-build`)
  over the new `.github/actions/nix-toolchain` composite, and `org.gradle.parallel=true` overlaps the
  frontend build, the backend `installDist` and the downloads inside the root build. Diff for the
  main-tree agent: `git diff main...roadmap/51-ci-verification`.
- 2026-10-14 (workspace, main-tree review round): the review found no correctness defect, so the
  item returns to `in-progress` for the quality gaps, all addressed:
  F2 the five heavy jobs now `needs: [backend-check, frontend-check]` (the roadmap's "fast gate, then
  integration and e2e") plus a single aggregate `ci` job (needs all seven, `if: always()`) as the one
  required check for branch protection; F3 the JDK is passed at the pin's patch level (21.0.12 —
  Temurin has no build for the flake's 21.0.12.1) with the comment corrected; F4 `appEnv` exports
  `LC_CTYPE=C.UTF-8` like devenv.nix and flake.nix; F5 both cache keys hash only the download pins
  and the Nix store cap drops to 4 GB; F6 the e2e job delegates to `./gradlew e2eSystemDeps` instead
  of inlining pnpm/Playwright installs; F7a the pin and key reads are guarded (`set -euo pipefail`,
  `jq -er`), F7b `GRADLE_USER_HOME` is derived with `${…:-$HOME/.gradle}`, F7c `integrationTest`
  depends on `runtimeSystemDeps` (and the README says so), F7d the ffprobe requirement is confirmed
  for all three pinned jars, F7e the `nix-devenv` job lints the workflows with the devenv's
  actionlint. F7f (the `libasound2t64` name on Debian ≤ 12) and F7g (the daemon-environment caveat)
  are informational and noted in the handoff. **F1 open**: the ticks rest on run 38624698425 at
  `394de0b1`, and the workflow changed afterwards — a green run on the final HEAD must be recorded
  here before the item can merge, which also exercises three jobs racing for one store cache key.
- 2026-10-14 (workspace, F1 closed; user-reported run): run [38631404614](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/38631404614)
  green on `41e514d0`, all eight jobs. The gating works: backend-check (2:06) and frontend (1:02) start
  at once, and e2e (8:40), backend-integration (3:45) and the three Nix jobs start only after the gate
  finished (20:23:52–55); the aggregate `CI (all checks)` runs at the end (3 s) with its "Every job
  succeeded" step correctly *skipped* — which is itself the proof that no dependency failed. The
  narrowed keys behave as designed: `stt-downloads-Linux-ee6000ea…` (1.0 GB) and
  `nix-Linux-87be17bf…` (2.2 GB, inside the new 4 GB cap) were written this run while the previous
  `…e83c5c06…` and `…aab54d68…` entries were restored through the key prefixes (accessed 20:25:01 and
  20:24:04), and the Gradle caches were restored as well. `nix build` took 2 s, `nix flake check` 4 s.
  Item 51 is `in-review` again; item 5 stays deferred to the post-merge nightly, and branch protection
  on the single `ci` check is the user's step before merging.
- 2026-10-14 (root agent, review round 2 + PR preparation): every review finding is closed, and the
  green run was verified against the current workflow content — HEAD `6183a5ab` touches only this
  file, and the run page (8/8 jobs green, `CI (all checks)` 3 s) was fetched directly. Four LOW
  notes were deliberately left for a later cycle instead of risking the verified run: the e2e job
  installs through the build's own `e2eSystemDeps`, so `e2e/`'s pnpm store cache is gone (cost
  measured as nil: 8:40 vs 8:47); the narrowed cache keys omit a renamed pin key silently
  (`jq -Sec '{…}'` drops a missing key — an array form would change the key instead); the README's
  `runtimeSystemDeps` block still shows the non-root invocation. The known-issues 429 row now
  records that PR runs restore *and* write the Gradle cache. Branch protection must require the
  single `CI (all checks)` job. Squashed to one commit on top of `main` (ADR 0050) for the PR.
- 2026-10-14: merged into `main` (head `f2137e92`, PR #1) after the user approved the diff; the run
  that verified it is the PR's own (8/8 jobs green, `CI (all checks)` 3 s). Two review rounds had
  preceded it with no correctness defect: the fast-gate gating plus the aggregate check, the
  patch-level JDK pin, `LC_CTYPE` in the bootstrap environment, the narrowed cache keys (download
  pins only) with the 4 GB store cap, the e2e job delegating its installs to the build's
  `e2eSystemDeps`, the guarded pin reads, `integrationTest` depending on `runtimeSystemDeps` and
  `actionlint` running over the workflows all landed in the squash. Item 5 (the nightly trigger) is
  confirmed by the first 03:00 Europe/Berlin scheduled run from `main`; branch protection requires
  the single `CI (all checks)` job. Spec and final test list moved here from
  `roadmap/51-ci-verification.md`.
- 2026-10-21 (amended by the user's decision in item 48): the six macOS legs run on every pull
  request and every push to main, not only nightly and item 48's PRs; the workflow's `roadmap/48-`
  gate is removed and item 48's spec records the decision. The Windows cadence is unchanged.
- 2026-11-04 (amended by roadmap item 85's no-wait lever): the three journeys (`e2e`, `macos-e2e`,
  `windows-e2e`) now need only `changes`, so they start as soon as the change classification has
  run instead of waiting for their platform's two check jobs. This is the only such exception:
  every other heavy job still carries `needs: [changes, <platform>-backend-check,
  <platform>-frontend-check]`, and the check jobs stay in `CI (all checks)`'s `needs`, so a red
  check still fails the required check even when the journeys passed. Both halves are pinned by
  `docs/check-ci-gating.test.ts` ("the three journeys start with the change classification, and the
  checks gate every other job"). The change was measured against item 85's five baseline runs,
  where the checks ended at +2:50…+3:53 of a 21–24-minute run.

## Amendment (roadmap item 90, 2026-11-03)

- The manual `macos-whisper-asset` and `windows-whisper-asset` jobs read the whisper version from
  `versions.json` and emit `whisper-cpp-<version>-<nix system>.tar.gz`; no tag or version is
  hardcoded in the workflow. `macos-ffmpeg-asset` and `windows-ffmpeg-asset` emit the 8.1.3 assets
  for the `ffmpeg-8.1.3` release.
- The CI matrix has no Intel macOS leg and no `darwin-x64` artifact: Apple Silicon is the only
  macOS platform, and an Intel Mac fails fast
  ([ADR 0065](../decisions/0065-own-artifacts-versioned-tags-nix-platform-keys.md)).
- `checkVersions` (run by the Linux/Windows/macOS check jobs) also holds `devenv.yaml` to
  `flake.lock`'s nixpkgs rev, so the bump's pin pair cannot drift.

## Amendment (roadmap item 85, 2026-11-07)

- `desktop-bundle-e2e` caches the built bundle on its own: it evaluates `.#desktop-bundle.drvPath`,
  restores `stt-bundle-<derivation>` with `actions/cache/restore` (exact key, no prefix fallback),
  builds the bundle only on a miss, and runs the packaged specs against that one file
  (`E2E_BUNDLE_FILE`, which `e2e/desktop/run-in-docker.ts` honours instead of building). The key
  changes exactly when the bundle would, so a change that touches nothing the bundle is built from
  skips its ~5-minute build (CI run #595's log). The shared Nix store entry cannot carry the bundle:
  a cache key's contents never change once saved.
- The bundle cache follows the Gradle cache policy: only `main` saves it (`actions/cache/save` on a
  miss); a pull request restores a matching entry and writes nothing. Before, this PR's ref held a
  370 MB `stt-bundle-<derivation>` entry and the repository stood at 10.31 GB against the Free plan's
  10 GB. A pull request that changes the bundle inputs misses and rebuilds on its own runs (~5
  minutes), which is exactly the change the key tracks.
- `jvm-toolchain` writes the Gradle cache on the default branch only
  (`cache-read-only: ${{ github.ref != 'refs/heads/main' }}`). With every branch writing, one pull
  request accumulated ten `gradle-dependencies-v2` entries and the repository's caches passed the
  Free plan's 10 GB, so GitHub evicted entries the whole matrix depends on — in two consecutive runs
  of one commit the bundle cache hit and then missed (runs #598 and #599). A pull request now
  restores `main`'s entries, which GitHub allows for the default branch.
- The journeys do not cache the built UI (roadmap item 85 tried and reverted it): a cache of
  `frontend/dist` keyed by `hashFiles` of the frontend inputs hit once (`#606`) and missed twice
  (`#605`, `#607`) on trees whose inputs are identical (verified by re-deriving the matched files and
  their content hash from git), so it was dead weight and an extra entry in the 10 GB budget.
  `e2eFrontend`'s `.ui-digest` already reuses a build inside one checkout, and a cold checkout pays
  the vite build once (~20–40 s), which the journeys' measured setup (~53 s Linux, ~98 s Windows)
  includes.
- Playwright's browsers need no cache of their own (roadmap item 85): the build installs them
  into `~/.gradle/stt-app/ms-playwright` (`PLAYWRIGHT_BROWSERS_PATH`'s default, `build.gradle.kts`),
  and `jvm-toolchain`'s downloads cache already restores that directory as `stt-downloads-<os>-…`.
  A separate cache keyed on the browser directory restored nothing (run #607: the step 0:01, no save).
