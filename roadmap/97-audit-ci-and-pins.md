# 97 — CI and pins from the 2026-11-06 audit: every leg checks the pins, every pin has one source, every CI block is written once

Status: draft
Depends on: [85](85-ci-wall-time.md) (it changes `ci.yml`'s job graph)
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
2. Fold the per-OS check jobs into one matrix job, or into composite actions where a matrix would
   change required job names. Move the desktop staging block and the pins-hash snippet into
   composite actions. Keep `docs/check-ci-gating.test.ts` and `docs/check-packaged-steps.test.ts`
   asserting the same guarantees over the new shape.
3. Make the Mesa checks call `nix/artifact-integrity-check.nix` and keep only their extra
   assertion.
4. Give the darwin pins one source that both `nix/versions.nix` and `nix/desktop-artifacts.nix`
   read, or a flake check that holds their URLs and sha256s equal. Read the ffmpeg asset version
   in `build.gradle.kts` and `ci.yml` from one declared pin.

## Open questions

- **Matrix or composite actions** for the check jobs: a matrix renames the jobs (`check
  (ubuntu-latest)` …), which changes what `CI (all checks)` `needs` and what the docs tests look
  for; composite actions keep the names. The workspace agent proposes, and the user decides at the
  first checkpoint.
- **Rows from `docs/known-issues.md`** of the same "pins declared twice" class could join this
  item:
  - the model sha256s duplicated in `devenv.nix` `enterTest`;
  - the node/pnpm home path built twice in the root and frontend `build.gradle.kts`;
  - the Temurin `jdk` pin never compared with `versions.json`;
  - the bytedeco ffmpeg 8.1.2 bootstrap skew.

  This draft leaves them in the backlog until the consultation.

## Test list

Draft, to settle with the user.

- [ ] (basic) Every platform's check job runs `checkVersions`; a guard test fails when a check
      job lacks it.
- [ ] (basic) A darwin `whisper-cli` or `ffmpeg` pin changed in one place only fails a flake check
      (or cannot happen, because there is one declaration).
- [ ] (basic) The ffmpeg asset version is declared once; the Gradle bootstrap and the CI asset job
      read it.
- [ ] (basic) The Mesa artifact checks use the shared integrity module and still fail on a
      mismatched, missing or stale pin, and on a direct source-Mesa input.
- [ ] (must not break) After the fold, every check, integration, e2e and packaged job still runs
      on the platforms it ran on, with the same steps. `docs/check-ci-gating.test.ts` and
      `docs/check-packaged-steps.test.ts` hold over the new shape, and the PR's CI run is green.
- [ ] (must not break) The pins hash that keys the caches is computed by one script, and a pin
      change still invalidates the caches it did before.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a CI and build item, which ADR 0046 exempts. The e2e
and packaged journeys running unchanged in the PR's run are part of its proof.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, from the 2026-11-06 code
  audit after a verification pass. The user chose one item for the audit's CI and build findings.
