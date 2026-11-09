# 97 — CI and pins from the 2026-11-06 audit: every leg checks the pins, every pin has one source, every CI block is written once

Status: done (roadmap item 97, approved 2026-11-09)
Depends on: [85](ci-wall-time.md) (it changes `ci.yml`'s job graph)
Blocks: —

## Goal

The version-pin gate runs on every platform's check job, as the CI spec already says. Each
artifact pin and hash is declared once or held equal by a check. The workflow's per-platform
copies (the check jobs, the desktop staging block, the pins-hash snippet) are each written once.

## Evidence

The audit read `main` at `06c304f1`; the root agent had each finding re-checked against the
files, which corrected two claims (noted below).

- **`checkVersions` runs only on Windows.**
  - `.github/workflows/ci.yml:630-632` runs `gradlew.bat checkVersions`.
  - The Linux and macOS check jobs run only `./gradlew -p backend check` (`:88`, `:241`), which
    never evaluates the root build script where `checkVersions` lives (`build.gradle.kts:27`).
  - `docs/specs/ci-verification.md:205` says the Linux, Windows and macOS check jobs all run it.
  - Pin drift is caught only while the Windows leg runs.
- **The workflow repeats itself per platform.**
  - Nine check jobs are three jobs copied per OS: `:77`, `:91`, `:114`; `:233`, `:245`, `:266`;
    `:621`, `:637`, `:658`. There is no `strategy: matrix` anywhere.
  - The desktop staging block is copied three times: `:383-425` (macOS DMG), `:744-830`
    (Windows zip), `:1281-1340` (Linux .deb). It runs cache, backend/UI/tool builds, the desktop
    and e2e installs, the desktop checks, the installer build and the `TEST_*`/`STT_*` exports.
  - `docs/check-ci-gating.test.ts` and `docs/check-packaged-steps.test.ts` read the workflow's
    jobs and steps by pattern, so a fold must keep them meaningful.
  - Item 85 changes only `needs` and caches in this file.
- **The pins-hash snippet is copied.** `.github/actions/jvm-toolchain/action.yml:65-67` and
  `.github/actions/nix-toolchain/action.yml:27-28` both compute
  `jq -Sec '{downloads, models, fixtures, whisperCpp, ffmpeg}'` plus sha256 (the Nix one also
  hashes the lock files).
- **The Mesa flake checks re-implement a shared module.**
  - `flake.nix:910-955` (`desktop-mesa-artifact-integrity`) and `:962-993`
    (`desktop-mesa-stale-pin`) restate the fixture, mismatch, missing and stale-pin gates of
    `nix/artifact-integrity-check.nix`.
  - Correction: they do not copy `nix/asset-pin-check.nix`, and they add one assertion the module
    lacks: no registered-Mesa derivation takes the source Mesa as a direct input.
- **Pins are declared twice.**
  - The darwin `whisper-cli` and `ffmpeg` URLs and sha256s exist in both `nix/versions.nix:56-60,
    73-74` and `nix/desktop-artifacts.nix:48-49,60-63`. The URLs and tags are already checked
    (`flake.nix:1181-1213` `whisper-asset-version`, `:1225-1260` `artifact-tags`); **the sha256s
    are compared nowhere**.
  - The `ffmpeg-8.1.3` version is a literal in `build.gradle.kts:96` and in `ci.yml:1192-1194,1233`
    (the source tarball URL and its sha256), while `whisperCpp` comes from `versions.json`.
  - Correction: the audit's "docs-check misses four test files" is wrong. `ci.yml:145` is the
    dependency-free subset that runs before `pnpm install`, and `:161`'s `pnpm --dir docs test`
    runs all ten.

## Proposal

1. Run `checkVersions` in the Linux and macOS check jobs too, so the spec's sentence is true.
2. Fold the per-OS check jobs into **matrix jobs** (the names change; the aggregator and the docs
   tests follow), and move the desktop staging block and the pins-hash snippet into composite
   actions. Keep `docs/check-ci-gating.test.ts` and `docs/check-packaged-steps.test.ts` asserting
   the same guarantees over the new shape.
3. Make the Mesa checks call `nix/artifact-integrity-check.nix` and keep only their extra
   assertion.
4. Give the darwin pins one source that both `nix/versions.nix` and `nix/desktop-artifacts.nix`
   read, or a flake check that holds their URLs and sha256s equal. Read the ffmpeg asset version
   in `build.gradle.kts` and `ci.yml` from one declared pin. The same treatment for the three
   joined rows: derive the `devenv.nix enterTest` model sha256s from `nix/models.nix`, share the
   node/pnpm home path between the two `build.gradle.kts`, and assert the manifest's `jdk` appears
   in every Temurin URL (or derive those URLs from it).

## Open questions

None. (Settled 2026-11-09, see "Decided".)

## Decided (consultation, 2026-11-09)

- **The per-OS check jobs fold into matrix jobs**, accepting that the job names change (`check
  (ubuntu-latest)` …). The aggregator's `needs` and the docs tests are updated in the same change,
  and the user updates the branch-protection required checks when the PR merges.
- **Three "pin declared twice" backlog rows join the item**: the model sha256s duplicated in
  `devenv.nix enterTest`, the node/pnpm home path built in both `build.gradle.kts`, and the Temurin
  `jdk` pin never compared with `versions.json`. **The bytedeco ffmpeg 8.1.2 skew stays in the
  backlog**: swapping the Linux bootstrap artifact is a packaging change, not a duplicated check.

## Test list

Approved 2026-11-09.

- [x] (basic) Every platform's check job runs `checkVersions`; a guard test fails when a check
      job lacks it.
- [x] (basic) A darwin `whisper-cli` or `ffmpeg` pin changed in one place only fails a flake check
      (or cannot happen, because there is one declaration).
- [x] (basic) The ffmpeg asset version is declared once; the Gradle bootstrap and the CI asset job
      read it.
- [x] (basic) The Mesa artifact checks use the shared integrity module and still fail on a
      mismatched, missing or stale pin, and on a direct source-Mesa input.
- [x] (must not break) After the fold, every check, integration, e2e and packaged job still runs
      on the platforms it ran on, with the same steps; the matrix jobs' new names are what
      `CI (all checks)` needs, the required checks are updated, and `docs/check-ci-gating.test.ts`
      and `docs/check-packaged-steps.test.ts` hold over the new shape. The PR's CI run is green.
- [x] (basic) The three joined pin pairs hold: the model sha256s come from `nix/models.nix`, the
      node/pnpm home path has one definition, and a Temurin URL whose patch differs from the
      manifest's `jdk` fails a check.
- [x] (must not break) The pins hash that keys the caches is computed by one script, and a pin
      change still invalidates the caches it did before.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a CI and build item, which ADR 0046 exempts. The e2e
and packaged journeys running unchanged in the PR's run are part of its proof.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, from the 2026-11-06 code
  audit after a verification pass. The user chose one item for the audit's CI and build findings.
- 2026-11-09: consultation (user): the check jobs fold into matrices, with the job names changing
  and the aggregator and required checks updated in the same change; three pin rows join the item
  (model sha256s, the node/pnpm home path, the Temurin `jdk`); the bytedeco skew stays in the
  backlog. Status stays `draft` until the updated test list is approved.
- 2026-11-09: approved by the user: the matrix fold, the three joined pin rows and the 7-line test
  list as updated. Status set to spec-approved.

## TDD log

- 2026-11-09 (line 1, every leg runs checkVersions) — commit 6638411c. Red: `node --test
  docs/check-ci-gating.test.ts` → "backend-check runs the backend build, so it runs checkVersions
  too". Green: the Linux and macOS check jobs run `./gradlew checkVersions` (Windows already ran
  `gradlew.bat`), and the guard reads whatever job carries `-p backend check`, so it survives the
  fold. `gradle checkVersions` printed "versions.json is in sync (Gradle 9.8.2, nixpkgs
  0bb2eedc20e4, Rust 1.99.0, Windows pins present)". No refactoring suggested: one step and one
  guard.
- 2026-11-09 (line 2, darwin pin sources) — commit 4a8f6839. Red: `node --test
  docs/check-pins.test.ts` → "nix/desktop-artifacts.nix repeats the whisper-cli aarch64-darwin
  sha256" (and the ffmpeg one; neither read versions.downloads). Green: nix/desktop-artifacts.nix
  imports nix/versions.nix and inherits the darwin url/sha256 pairs; docs/check-pins.ts is the guard
  and its planted-duplicate test runs in the docs gate and the dependency-free CI subset. No
  refactoring suggested.
- 2026-11-09 (line 3, one ffmpeg asset version) — commit df12c392. Red: "versions.json has no
  ffmpegAsset". Green: nix/versions.nix declares `ffmpegAsset = "8.1.3"` once and interpolates the
  darwin/Windows download URLs and desktop-artifacts' Linux/darwin URLs; the flake renders it into
  versions.json; the Gradle bootstrap and the Windows asset job read it. `gradle checkVersions`
  green. Nix evaluation (versions-sync) is the user's.
- 2026-11-09 (line 6, the three joined pairs) — commit a2543a85. Red: the planted devenv.nix model
  hash was accepted (0 !== 1). Green: enterTest reads models.meta.whisper/speaker.sha256; the root
  build defines sttNodeHome once and frontend/build.gradle.kts reads it; check-pins asserts every
  Temurin URL names versions.json's jdk in the release tag and the file name. `gradle help`
  configured the frontend against the shared path; `gradle checkVersions` green. The three fixed
  rows left docs/known-issues.md; the bytedeco skew stays. No refactoring suggested.
- 2026-11-09 (line 7, one pins hash) — commit dac268cb. Red: "jvm-toolchain computes its key
  through the pins-hash action". Green: .github/pins-hash.sh hashes the versions.json pin set (and
  with --nix the lock, devenv and nix files), .github/actions/pins-hash exposes it, and both
  toolchains call it; the sandbox test proves a pin change changes both digests. The real-tree
  digests are fe3b5e45… (downloads) and 09494654… (Nix store). No refactoring suggested.
- 2026-11-09 (line 4, Mesa uses the shared module) — commit 528ea183. Red: "the Mesa check calls
  the shared module". Green: artifact-integrity-check.nix takes `artifactsKey` (default `attr`);
  flake.nix's desktop-mesa-artifact-integrity calls it and keeps only the direct-source-Mesa
  assertion; desktop-mesa-stale-pin is gone (the module carries the stale gate) and the darwin
  allowlist follows. The module's own mismatch/missing/stale gates are exercised by nix flake check
  (the user's); the docs test pins the shape. No refactoring suggested.
- 2026-11-09 (line 5, matrix fold) — commit 1db6e192. Red: "check is a job". Green: the nine
  per-OS check jobs are one `check (${{ matrix.os }})` matrix on ubuntu-24.04, macos-15 and
  windows-2025; every platform job and CI (all checks) need `check`; the desktop staging block the
  three packaged jobs repeated is .github/actions/desktop-stage. actionlint green; the three docs
  workflow tests (19) and the whole docs gate (44 tests, tsc, prettier) green. Discovered: a
  platform job's `needs: check` now waits for all three legs, not only its own; the journeys still
  start at `changes` (item 85), and the PR's run measures the new wall time. Branch protection: the
  matrix legs `check (ubuntu-24.04)`, `check (macos-15)` and `check (windows-2025)` are new status
  checks; the required check stays `CI (all checks)`, which needs `check` and fails on any red leg.
  No refactoring suggested.

- 2026-11-09 (fix, the user's `direnv reload`): `error: attribute 'ffmpegAsset' missing` —
  nix/versions.nix had the version only in its `let`, while flake.nix and nix/desktop-artifacts.nix
  read it off the imported attrset. Red: the strengthened docs/check-pins.ts printed "nix/versions.nix
  must export ffmpegAsset in its returned attribute set"; Green: `inherit ffmpegAsset;`, and the
  sandbox test removes that line and expects the failure. The user re-runs `direnv reload` and
  `nix flake check`.

- 2026-11-09 (user-run): `direnv reload` is green after `992aaadd`, confirming the `ffmpegAsset`
  export; `devenv test` is green. `nix flake check` stopped while fetching the Mesa release asset
  (`mesa-26.2.6/stt-bundle-mesa-vulkan-26.2.6-x86_64-linux.tar.zst`, sha256 `0b43fa20…`): GitHub
  answered HTTP 504. The asset's derivation and pin are unchanged by this item, and the existing
  `desktop-mesa-prebuilt` check already fetches the same asset, so this is a transient release-host
  failure to re-run, not a verdict on the diff.

- 2026-11-09 (user-run, second pass): `nix flake check` is green on x86_64-linux (7m30s); the
  earlier 504 did not repeat. aarch64-darwin was omitted as incompatible, as the warning says; its
  ffmpeg pin URL is still forced by `artifact-tags` and its whisper pin by
  `whisper-asset-version`, and the macOS `nix flake check` job covers the darwin checks. This run
  also proves `versions-sync` matches the rendered `versions.json` with `ffmpegAsset`, and the
  shared Mesa module's gates. The devenv's root `gradle check` is green in 44s (configuration of
  the root/frontend build with the shared `sttNodeHome`); the backend sources are unchanged.

- 2026-11-09 (CI run 42584309018, run #626): dispatched by the user on `7c75c3dd`, which already
  carries the `ffmpegAsset` export fix (`992aaadd`). At ~2 minutes: `changes` green, docs-check
  running, the three `check (…)` matrix legs running the JVM toolchain with no failures, the three
  e2e legs started, and the manual assets skipped. The Nix and packaged jobs are created after
  `check` completes. Local HEAD is `72df54f7` (the final log commit only), so that commit must be
  pushed before the PR.

- 2026-11-09 (CI run 42584309018, failures): the three `check` legs are red. `check (ubuntu-24.04)`
  failed in `actions/setup-java` — GitHub returned HTTP 504 downloading the Temurin tarball, the
  same release-host failure as the earlier Nix run — and `check (macos-15)` / `check (windows-2025)`
  failed `:ktlintCheck`: `build.gradle.kts:416:1: Expected a blank line for this declaration
  (standard:blank-line-before-declaration)`, from the `sttNodeHome` edit. Fix: `gradle -p backend
  ktlintFormat` added the blank line before `nodeInstallDir`; `gradle -p backend ktlintCheck` is
  green, and the full `gradle -p backend check` is green locally (BUILD SUCCESSFUL in 4m06s, eight
  tasks executed). The Linux leg is a re-run, not a code change.

- 2026-11-09 (CI run 42585047746, run #627): **green** on `93e67b2d` (the ktlint fix). Every job
  succeeded — the three `check (…)` legs, the three e2e legs, both integration jobs, all packaged
  jobs (Linux .deb, flake, bundle, macOS DMG, Windows zip), the three Nix jobs, docs-check and
  `CI (all checks)`; the manual asset jobs were skipped as designed. End-to-end 30m06s including
  the ~12-minute queue, so ~18 minutes running. This is the item's green matrix run.

## In-review note

- 2026-11-09: the 7-line test list is green and Status is in-review. The workspace sandbox cannot
  run Nix or macOS/Windows; the user ran `nix flake check` (green on x86_64-linux), `devenv test`
  and `gradle check` on the branch. CI run 42585047746 (#627) on `93e67b2d` is green with every job
  success, `CI (all checks)` included. Local HEAD `648d93f5` is that run head plus the docs-only
  backend-check log and must be pushed for the PR. The user confirms at merge that branch
  protection still requires `CI (all checks)` and sees the three new `check (<runner>)` statuses.
- 2026-11-09: reviewed by the root agent and approved by the user: the matrix fold read in the
  workflow (one `check` job, three legs, `checkVersions` on each, `CI (all checks)` needs `check`,
  the nine old job names gone), the new pin check green on the tree with its planted-drift tests
  (23 docs tests pass), and run 42585047746 (#627) green on `93e67b2d` with every job success or
  skipped. Squashed for the PR. Status: done.
