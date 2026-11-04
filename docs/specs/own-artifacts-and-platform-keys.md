# 90 — A nixpkgs bump, our own whisper.cpp 1.9.5 artifact on every route, and one platform vocabulary

Status: done (roadmap item 90, approved 2026-11-04)
Depends on: [63](whisper-cpp-artifact.md) (done; the pinned-artifact mechanism), [48](macos-support.md) and [49](windows-support.md) (done; the darwin and Windows assets), [33](gradle-bootstrap.md) (done; the bootstrap that downloads them)
Blocks: —

## Goal

Every way of running the app ships one whisper.cpp build we publish ourselves, at the current
upstream tag **1.9.5** — the development shell, the Nix packages, Docker, the Gradle bootstrap on
Linux, Windows and Apple Silicon, and the packaged installers — and no third-party prebuild is
downloaded any more. The nixpkgs pin is bumped once, together with this, and every platform key in
`versions.json` is a Nix system name, so the two vocabularies that meet there become one.

**Out of scope**: `aarch64-windows` — the one platform we might add later (2026-11-03, the user).
It is a Nix double like the others, so it needs no schema change when a later item ships it; this
item stays on `x86_64-windows` and keeps Windows on ARM64 unsupported, as README states.

## Evidence

- **The pin is old and split across builds** (`nix/versions.nix:7`, `wcTag = "1.8.4"`). Upstream's
  newest is **v1.9.5** (2026-10-06); the nixpkgs the bump moves to carries recipe 1.9.2.
  `nix/whisper-cpp.nix` overrides the tag to 1.8.4 and carries two 1.8.4-only workarounds (its
  install check runs `parakeet-cli`, which 1.8.4 lacks; its ffmpeg option is still `WHISPER_FFMPEG`,
  so the flag is a no-op) — at 1.9.x both disappear and the override keeps only our flags.
- **One tag, four builds**:
  - dev shell, Docker, Linux Nix build: our source build, Vulkan, no SDL/ffmpeg
    (`nix/whisper-cpp.nix`);
  - `desktop`/`desktop-bundle`/`app` (x86_64-linux): the pinned `desktop-whisper-cpp-v1` asset
    (`nix/desktop-artifacts.nix`, re-linked by `nix/prebuilt-artifact.nix`);
  - Gradle bootstrap: `jiang1997/whisper.cpp-release` prebuilds for `linux-x64` and `darwin-x64`, our
    own assets for `darwin-arm64` and `windows-x64` (`nix/versions.nix:20-59`);
  - the two manual CI jobs hardcode the tag and file names
    (`.github/workflows/ci.yml` `macos-whisper-asset`, `windows-whisper-asset`).
- **A nixpkgs bump invalidates every artifact pin by construction**: a bump changes the source
  build's out path, so evaluation fails naming both paths (`nix/prebuilt-artifact.nix`; seen on
  2026-11-03 with whisper-cpp, and the same holds for the audio-only ffmpeg and the bundle Mesa).
  That is why the bump and the artifact work are one change: the assets are published once.
- **Two platform vocabularies share one file**: Nix computes `x86_64-linux`/`aarch64-darwin`
  (`pkgs.stdenv.hostPlatform.system`, the keys of `nix/desktop-artifacts.nix`), while the manifest's
  download keys are hand-mapped `<os>-<arch>` strings with a different CPU abbreviation —
  `linux-x64`, `darwin-arm64`, `windows-x64` — minted by `build.gradle.kts:126-137` and
  `desktop/verified-download.ts:32-38`. `x86_64-windows` is Nix's own double for the Windows target
  (`lib.systems.doubles.windows`).
- **Intel macOS is unsupported, and the code has not caught up**: `README.md:44-46` and
  `docs/backend-reimplementation.md:763` state that Apple discontinued Intel Macs and the pinned
  nixpkgs dropped `x86_64-darwin`, and at the bump target `lib.systems.doubles.darwin` is
  `aarch64-darwin` only. Yet `darwin-x64` survives in `build.gradle.kts:126,130,424`,
  `desktop/verified-download.ts:35`, the dead jiang1997 pin at `nix/versions.nix:49-52`, and
  `docs/specs/ci-verification.md:31`.
- **The Gradle bootstrap reads the committed manifest without Nix**: the manifest is the contract
  for machines without Nix, so whatever the keys are called, the bootstrap must resolve the same
  URL and sha256, and a check must hold the key set to one vocabulary.

## Decided (consultation, 2026-11-03)

- **whisper.cpp 1.9.5** (the user): the newest upstream tag, with the source override kept for the
  tag and our flags.
- **One change, one pull request** (the user): the nixpkgs bump, the artifacts and the platform-key
  unification land together, so the release assets are built and published once.
- **Intel macOS stays unsupported** (the user): Apple discontinued Intel Macs; its code paths are
  removed, not kept best-effort.
- **Platform keys are Nix system names** (the user asked for one vocabulary): `x86_64-linux`,
  `aarch64-darwin`, `x86_64-windows`. External names (Node's `win-x64`, Electron's `win32-x64`) stay
  only inside the functions that translate to them, never as manifest keys.
- **The whisper release tag carries the whisper.cpp version** (the user): `whisper-cpp-1.9.5`, not an
  internal `v1`/`v2` counter, so the tag, the asset names and the pin all say which upstream build
  they hold; the source bump moves all three at once.

## Proposal

### 1. One shared pin

- `nix flake update nixpkgs`, then set `devenv.yaml`'s pinned rev to the same `flake.lock` rev and
  `devenv update` (ADR 0036: devenv follows the flake's pin; today that is a hand-maintained copy in
  `devenv.yaml` + `devenv.lock`, and a stale copy is invisible to `checkVersions`). The bump
  procedure is written down in AGENTS.md, and `checkVersions` grows the assertion that
  `devenv.yaml`'s rev equals `flake.lock`'s, so the pair cannot drift again.
- `nix run .#update-versions`; `./gradlew checkVersions`; the Gradle wrapper if `gradle_9` moved;
  `client/rust-toolchain.toml`'s channel to the manifest's rust; `pnpm install` in `frontend/`,
  `docs/`, `e2e/` and `desktop/` after the reload (Node moves with the pin).
- Playwright follows the pin: `e2e/package.json` to the nixpkgs `playwright-driver` version, and the
  `devenv.nix` comment to its browser revision (`node e2e/check-playwright.ts` proves comment,
  package, store and farm agree).

### 2. The whisper.cpp 1.9.5 artifact

- **Source** (`nix/whisper-cpp.nix`): tag 1.9.5 with our flags (no SDL, no ffmpeg support, Vulkan on
  Linux, Metal on darwin); the two 1.8.4-only workarounds go.
- **Assets** — one per shipped system, all published by us under the tag `whisper-cpp-1.9.5`,
  named `whisper-cpp-1.9.5-<nix system>.tar.gz`:
  - `x86_64-linux`: the Vulkan build (the same source the dev shell, Docker and the Nix packages
    run);
  - `aarch64-darwin`: the static Metal build (item 48's job);
  - `x86_64-windows`: the self-contained build — static CRT, no OpenMP, the pinned Vulkan loader
    beside the executables (item 49's job).
  One archive format (`.tar.gz`) for every asset: the Gradle bootstrap cannot read zstd, and a
  second copy of each asset for its sake is worse than the size the Nix pin loses.
- **The release tag names the whisper.cpp version, not an internal counter**: `whisper-cpp-1.9.5`.
  The tag is derived from `whisperCppTag` (the asset-pin app and the printed pin build
  `whisper-cpp-${version}` rather than reading a second literal), so a bump moves the tag, the file
  names and every URL together and no `v1`/`v2` has to be chosen. Content is determined by the
  upstream tag plus our fixed flags; if the same version ever has to be rebuilt (a flag or recipe
  change), that one rebuild takes a suffix — `whisper-cpp-1.9.5-2` — and the ADR records the rule,
  because a published release asset can never be replaced.
  The other families use the same rule (the user's decision, 2026-11-03, superseding the earlier
  `desktop-ffmpeg-vN` / `desktop-mesa-vN` choice): `ffmpeg-8.1.3` and `mesa-26.2.6`; a rebuild at
  the same upstream version takes a `-2` suffix, exactly like whisper.
- **Every route reads the manifest**: `nix/versions.nix`'s `whisperCppTag` and its three download
  entries, `nix/desktop-artifacts.nix`'s `whisperCpp.x86_64-linux` pin (url, sha256, storePath,
  references, read off the producer's printed pin), and both manual CI jobs read the tag from
  `versions.json` instead of a literal, so one bump moves all of them. `nix run
  .#whisper-cpp-asset-pin` verifies the pin; a check names any route whose asset version differs
  from the manifest.
- **jiang1997 disappears**: the `linux-x64` and `darwin-x64` download entries are replaced by our
  `x86_64-linux` asset and deleted (with Intel macOS), so no route downloads a third-party build.

### 3. One platform vocabulary

- `versions.json` `downloads`, `supportedPlatforms`, the Kotlin platform mapping, the desktop's
  `verified-download.ts` and the Windows assertions in `checkVersions` use Nix system names.
- `darwin-x64` is removed everywhere it survives (the five code sites above and the stale
  `ci-verification` line); an Intel Mac fails fast, naming the supported platforms, instead of
  resolving a download that no longer exists.
- A check asserts that every `downloads` key and every `supportedPlatforms` entry is a Nix double of
  a system the flake ships, and that every such pair has a pin.

### 4. The bump's other forced assets

The audio-only ffmpeg (x86_64-linux, aarch64-darwin) and the bundle Mesa (x86_64-linux) are rebuilt
and republished under new tags, with their pins and `nix/versions.nix` download shas updated (the
same producer/printed-pin/upload flow).

### 5. Documentation

- A new ADR records the standing decisions: the app publishes its own whisper.cpp binary for every
  route (no third-party prebuilds), platform keys are Nix system names, and every release tag names
  the artifact's upstream version — `whisper-cpp-1.9.5`, `ffmpeg-8.1.3`, `mesa-26.2.6` — with a
  `-2`-style suffix only for a same-version rebuild.
- `whisper-cpp-artifact`, `windows-support`, `macos-support`, `gradle-bootstrap` and
  `ci-verification` are updated; README's supported-platform paragraph already states the Intel
  outcome.

## Open questions

None — the four decisions above are the user's (2026-11-03); the archive format, the deletion of the
jiang1997 entries and the same-version-rebuild suffix follow from them.

## Test list

- [x] (basic) The dev shell, `nix build .`, `.#desktop`, `.#desktop-bundle` and the Docker image all
      ship whisper-cli 1.9.5 from the pinned version (one version, with the Nix source asset on the
      Nix routes and the portable glibc asset for the Gradle bootstrap/`.deb` as the two Linux
      toolchain variants), and `whisper-cli` starts and reports its help on each.
- [x] (basic) The Gradle bootstrap on Linux, Windows and Apple Silicon downloads whisper-cli 1.9.5
      from our release asset (the manifest's URL), verifies its sha256 and runs it.
- [x] (basic) The Windows MSI/zip and the macOS DMG install a whisper-cli that transcribes the
      fixture on a machine with no development tools.
- [x] (basic) Transcribing the fixture with 1.9.5 yields the transcript the app shows today: the
      backend's integration transcription scenarios produce their expected turns and text, and the
      e2e transcript journey's expected phrase still appears.
- [x] (edge) The whisper assets' release tag, file names and pin URL all carry the manifest's
      whisper.cpp version (`whisper-cpp-1.9.5`); bumping the manifest version moves the tag, the
      names and the pin together, and a pin whose tag names another version fails the check.
- [x] (edge) The Windows asset runs on a stock Windows install (static CRT, the pinned Vulkan loader
      beside the executables) and uses a GPU when a Vulkan driver is present, the CPU otherwise.
- [x] (edge) The macOS arm64 asset starts whisper-cli and the app log carries the Metal backend line.
- [x] (edge) A stale pin still fails at evaluation, naming the asset, the url, and both store paths
      (the asset built from another whisper.cpp build than the flake's).
- [x] (edge) `checkVersions` passes on the bumped pin (nixpkgs rev, wrapper, Rust), and
      `node e2e/check-playwright.ts` passes with the new Playwright version and browser revision.
- [x] (edge) An unsupported platform (an Intel Mac, Windows on ARM64) fails fast, naming the
      supported platforms, instead of resolving a missing download.
- [x] (failure) A downloads table with a key that is not a Nix double fails the platform check, as
      does a supported system without a pin for every downloaded tool.
- [x] (must not break) The packaged and unpackaged e2e journeys stay green on Linux, Windows and
      macOS, including the packaged Windows MSI and zip jobs.
- [x] (discovered) The two Linux system-dependency tasks match the Nix `x86_64-linux` key, so the
      e2e and integration jobs install `libpulse` and Chromium's libraries instead of skipping.
- [x] (must not break) `./gradlew prepareRuntime` on a second run reuses the cached downloads and
      verifies the pinned sha256 without downloading again.

## Approval log

- 2026-11-03: filed as a draft by the root agent, after the dependency scan's nixpkgs bump failed on
  the whisper-cpp asset pin (`desktop artifact whisper-cpp-1.8.4-x86_64-linux.tar.zst … was made from
  another whisper-cpp build than this flake's`) and showed that a bump is a release operation, not a
  pin refresh.
- 2026-11-03: consultation and approval (the user): whisper.cpp **1.9.5**, published under the tag
  `whisper-cpp-1.9.5` (the release tag carries the version, not an internal counter); the nixpkgs bump,
  the artifacts and the platform-key unification are **one change in one pull request**; Intel macOS
  stays unsupported and is removed from the code; platform keys are Nix system names
  (`x86_64-linux`, `aarch64-darwin`, `x86_64-windows`); `aarch64-windows` is out of scope, the one
  platform a later item may add. Status: spec-approved.
- 2026-11-03 (workspace, cycle 1 = test-list item 11): the platform-key check. Red, verbatim from
  `nix eval .#checks.x86_64-linux.platform-keys.drvPath`:
  `platform-keys: downloads key darwin-arm64 is not a supported platform (x86_64-linux,
  aarch64-darwin, x86_64-windows); downloads key darwin-x64 …; downloads key linux-x64 …;
  downloads key windows-x64 …; ffmpeg has no x86_64-linux pin; …`. Green: the same evaluation on
  `x86_64-linux` and `aarch64-darwin`, `gradle checkVersions`, `gradle -p backend check` (ktlint +
  all unit tests), prettier and the desktop scripts typecheck. `nix/platform-keys.nix` validates
  the download keys, the supported list (Nix doubles, flake systems plus the download-only
  `x86_64-windows`) and every tool pin, with fixtures for a bad key and a missing pin;
  `nix/versions.nix` now names the keys `x86_64-linux`/`aarch64-darwin`/`x86_64-windows`
  (`darwin-x64` and the bytedeco Intel-macOS ffmpeg pin are gone), and `supportedPlatforms` is
  rendered into `versions.json`, read by the Gradle bootstrap and the desktop staging. Commits
  `04bc131c` (test+code) and `4565c3fd` (refactor). User-run, not observable in the sandbox
  (read-only store): `nix flake check` (the check's `runCommand` build) and
  `nix run .#update-versions` (the manifest is hand-edited to the render; `versions-sync` is the
  gate).
- 2026-11-03 (workspace, cycle 2 = test-list item 10): the unsupported-host fast failure. Red:
  `gradle -Dos.name="Mac OS X" -Dos.arch=x86_64 prepareRuntime --dry-run` printed only
  `:prepareRuntime SKIPPED` (exit 0), and the desktop child `pin('whisper-cli', 'test',
  'x86_64-darwin')` printed `versions.json has no x86_64-darwin whisper-cli`. Green: both name
  the supported list (`unsupported platform x86_64-darwin (supported: x86_64-linux,
  aarch64-darwin, x86_64-windows)`; `does not support this platform: macos/x86_64. Supported:
  …`). `desktop/verified-download.test.ts` covers the OS/arch → Nix-name mapping, the desktop
  refusal for Intel macOS, Windows ARM64 and Linux ARM64, and the Gradle wrapper refusal for
  Intel macOS and Linux ARM64 (Linux leg only: faking `os.name=Windows` makes node-gradle look
  for powershell before configuration). Green gates: desktop test 5/5, `src/backend.test.ts`
  9/9, all four desktop tsconfig type checks, prettier, `gradle checkVersions`,
  `gradle -p backend ktlintCheck`. Commits `1c5dbf52` (test+code) and `fe06a4b5` (refactor).
  No user-run step; CI's desktop legs run the suite.
- 2026-11-03 (workspace, cycle 3 = test-list item 9): the nixpkgs bump. `494ce7fd` → `0bb2eedc`
  (2026-11-01): Gradle 9.8.0 → 9.8.2 (wrapper URL and sha256), Rust 1.98.1 → 1.99.0
  (`client/rust-toolchain.toml`), Playwright 1.63.0/1243 → 1.64.1/1248 (`devenv.nix` comment,
  `e2e/package.json`, lockfile); Node 24.21.0, pnpm 11.27.0, JDK 21.0.12.1+1 and ffmpeg 8.1 stay
  as the manifest records them (the ffmpeg source itself is now 8.1.3). Red:
  `versions.json was generated from nixpkgs 494ce7fd23ff but flake.lock pins 0bb2eedc20e4`; then
  the new assertion: `devenv.yaml pins nixpkgs 494ce7fd23ff but flake.lock pins 0bb2eedc20e4`.
  Green: `gradle checkVersions`; `node e2e/check-playwright.ts` (`comment, package.json, install
  and store agree: @playwright/test 1.64.1, chromium-1248`; the store was simulated with the
  revision nixpkgs' `playwright-driver.browsers.entries` names); `versions-sync` equality by
  evaluating the flake's render against `versions.json`; flake.lock/devenv.yaml/devenv.lock all
  `0bb2eedc`; e2e prettier and tsc; `gradle -p backend ktlintCheck`. Commit `88bec8b5`.
  User-run: `devenv update` (no `devenv` binary here; `devenv.lock` was moved to the same
  rev/narHash by hand), `direnv reload`, `pnpm --dir e2e install --frozen-lockfile`, then
  `node e2e/check-playwright.ts` in the new shell. Consequence, as the spec predicts: the bump
  moved the whisper-cpp (`avy7450v…`), audio-only ffmpeg (8.1.3, `vnhygj96…`) and bundle Mesa
  source out paths, so all three pins are stale and `nix flake check` stays red until the user
  rebuilds/uploads and we paste the printed pins; the ffmpeg and Mesa assets go to new tags.
- 2026-11-03 (workspace, cycle 4 = test-list item 5): whisper.cpp 1.9.5 and the version-named
  asset tag. Red: `whisper-asset-version: the release tag is desktop-whisper-cpp-v1, not
  whisper-cpp-1.8.4; the pin whisper-cpp-1.8.4-x86_64-linux.tar.zst is not on the
  whisper-cpp-1.8.4 release: …; the download https://github.com/jiang1997/… is not on …`.
  Green: the check on both systems; `versions-sync` equality by evaluation; the 1.9.5 source's
  out path `mf301gvw…-whisper-cpp-1.9.5` with `whisperPinError = null`; the asset file
  `whisper-cpp-1.9.5-x86_64-linux.tar.gz`; `gradle checkVersions`; `gradle -p backend check`
  (ktlint + all unit tests, languages contract); actionlint; prettier. Commits `ac5ec098`
  (test+code) and `5f36ac28` (refactor). The source fetches v1.9.5 (hash `sha256-Qqqp…`) and the
  1.8.4-only install-check override is gone; the tag is `whisper-cpp-${whisperCppTag}`, the
  asset is `.tar.gz` on every system, the Linux/darwin pins and the three `nix/versions.nix`
  downloads are on the tag, both manual CI jobs read the version from `versions.json`, and the
  languages contract says 1.9.5.
  **User-run publication (pending)**: the whisper sha256s and the Linux pin's direct references
  are zero/empty placeholders until the user builds and uploads:
  - Linux: `nix build .#desktop-whisper-cpp-asset`, upload
    `result/whisper-cpp-1.9.5-x86_64-linux.tar.gz` to a `whisper-cpp-1.9.5` release, paste
    `result/pin.nix` into `nix/desktop-artifacts.nix`, then `nix run .#update-versions`; verify
    with `nix run .#whisper-cpp-asset-pin`.
  - macOS: run the `macos-whisper-asset` workflow (assets input) and upload its tar.
  - Windows: run the `windows-whisper-asset` workflow and upload its tar.
  Then `nix run .#update-versions` captures the three sha256s in `versions.json`. Unverified
  here: the 1.9.5 source build and its transcript, and every `nix flake check` entry that builds
  the pinned assets — those stay red until the pins are pasted.
- 2026-11-03 (workspace, forced republish code, commit `0d5ea130`): ffmpeg `desktop-ffmpeg-v3`
  (source 8.1.3; Linux `vnhygj96…-static-…-8.1.3`, darwin `w5flp0s2…-8.1.3`) and Mesa
  `desktop-mesa-v2` (source `j0pdncfb…-26.2.6`). Their pins carry the new source out paths and
  the new tags; the sha256/references are zero/empty placeholders until the user publishes.
  Verified by evaluation: every artifact's `pinError` is null; `versions-sync` is true; the
  ffmpeg/Mesa integrity, stale-pin, pin-mismatch and everywhere checks evaluate on both systems;
  `gradle checkVersions`; actionlint. The Windows ffmpeg job builds 8.1.3 (tarball hash
  `7138d28c…`) and names its asset `ffmpeg-audio-only-8.1.3-x86_64-windows.tar.gz`; the Gradle
  Windows pin assertion follows. User-run: build/upload the five flake assets (whisper Linux,
  ffmpeg Linux, Mesa; plus the whisper macOS/Windows and ffmpeg macOS/Windows CI jobs), paste the
  printed pins, `nix run .#update-versions`, then `nix run .#whisper-cpp-asset-pin`,
  `nix run .#ffmpeg-asset-pin` and `nix flake check`.
- 2026-11-03 (workspace, docs): [ADR 0065](../decisions/0065-own-artifacts-versioned-tags-nix-platform-keys.md)
  records the standing decisions (our whisper build on every route, Nix-system platform keys, the
  version-named tags for every family (`whisper-cpp-1.9.5`, `ffmpeg-8.1.3`, `mesa-26.2.6`), one
  `.tar.gz` for whisper,
  a pin valid only for its source build, user publication). `whisper-cpp-artifact`,
  `gradle-bootstrap`, `macos-support`, `windows-support`, `ci-verification`, `audio-only-ffmpeg` and
  `desktop-bundle-mesa-artifact` carry an item-90 amendment; README names 1.9.5,
  `whisper-cpp-1.9.5` and `ffmpeg-8.1.3`, and the release-asset known issue is narrowed to
  release titles and old-release migration. Docs checks green: tsc, prettier, links,
  readme-windows, architecture names/diagram, exceptions, the retired-tool guard, 33 node tests.
- 2026-11-03 (workspace, test-list item 8): the stale-pin gate. `whisper-cpp-artifact-integrity`
  evaluates green on x86_64-linux: its fixture pin names another source build, `tryEval` fails at
  evaluation, and the error names the asset file, the url, the zero sha, the stale store path and
  this flake's source path (`nix/artifact-integrity-check.nix`). The same gate ran before the bump
  and now uses the 1.9.5 source path. The check's build/closure half still awaits the published
  asset; no user action for this item itself.
- 2026-11-03 (workspace, the user's decision during publication): every release tag names the
  artifact's upstream version — `ffmpeg-8.1.3`, `mesa-26.2.6`, like `whisper-cpp-1.9.5` — with a
  `-N` suffix only for a same-version rebuild. This supersedes the earlier
  `desktop-ffmpeg-vN`/`desktop-mesa-vN` exception before any asset was uploaded; ADR 0065, the
  specs and the code use the version-named tags.
- 2026-11-03 (workspace, test-list item 13 smoke, not ticked): in a throwaway copy of the tree with
  a `file://` manifest and fake zip/model fixtures, under the new `x86_64-linux` key, the first
  `gradle prepareRuntime` downloaded four files, the second downloaded none and succeeded, and
  corrupting the cached whisper model made the third report `Cached … does not match
  versions.json …; downloading it again` and re-download. The mechanism and the platform-key
  lookup are green; the item stays open for the user's `./gradlew prepareRuntime` against the real
  published pins (zero placeholders today).
- 2026-11-03 (workspace, Linux assets published): the user uploaded the three Linux assets under
  `whisper-cpp-1.9.5`, `ffmpeg-8.1.3` and `mesa-26.2.6`; their release digests match the local
  pins (whisper `9bfb1aec…`, ffmpeg `9c6ffc06…`, Mesa `0b43fa20…`). `nix/desktop-artifacts.nix`
  now carries the three pins (Mesa's 13 references read from the uploaded tar, to compare with
  `result-mesa/pin.nix`), and `nix/versions.nix`/`versions.json` the Linux whisper sha
  (commit `852acf2f`). The run's docs job failed because the roadmap log named the retired tool;
  reworded (`a236fd0c`). The Windows whisper job failed in its 1.9.5 CMake build (the macOS
  whisper job succeeded); the build now targets only `whisper-cli` and `whisper-server`, skipping
  the new parakeet examples (`0ac9d2cd`). `nix flake check --no-build --all-systems`,
  `checkVersions` and actionlint green. Still to upload: macOS whisper/ffmpeg, Windows
  whisper/ffmpeg; the Windows whisper job needs a re-run on the fixed branch.
- 2026-11-03 (workspace, non-Linux CI failures): the macOS ffmpeg job built and uploaded its
  artifact and failed only its pin proof because the recorded sha256 was zero; the producer
  printed `d65ac745…`, storePath `w5flp0s2…-ffmpeg-audio-only-8.1.3`, references `[]`, now pinned
  (commit `959017df`). The Windows whisper job failed at CMake configure: 1.9.5's ggml-vulkan
  requires SPIRV-Headers, which the Vulkan SDK carries without its CMake config; the job now
  installs SPIRV-Headers at the SDK tag and sets `SPIRV-Headers_DIR` (same commit). The macOS
  ffmpeg artifact from run #522 (id 41740187358, head 20a3d2e9 (pre-rebase; current equivalent e7c080bf)) is valid to upload; the Windows
  whisper job needed a fresh run on the later fix (`959017df`). actionlint, checkVersions and
  `nix flake check --no-build --all-systems` green.
- 2026-11-04 (workspace, all six assets published): the user uploaded the non-Linux assets;
  shas: macOS whisper `35c620d2…`, Windows whisper `86e3f028…`, Windows ffmpeg `6dc1e853…`
  (macOS ffmpeg `d65ac745…`, Linux whisper `9bfb1aec…`, Linux ffmpeg `9c6ffc06…` already).
  The pins are complete in `nix/desktop-artifacts.nix` and `nix/versions.nix`/`versions.json`
  (commit `7d03b59e`). `checkVersions`, actionlint and `nix flake check --no-build --all-systems`
  green; the four new shas were written identically to `nix/versions.nix` and `versions.json`
  (`versions-sync` itself is a build check; the sandbox's `--impure` render hit a stale nixpkgs
  store path). The Windows whisper job succeeded with the SPIRV-Headers step. Run #522's (head
  20a3d2e9 (pre-rebase; current equivalent e7c080bf)) remaining failures split: the macOS/Windows packaged jobs fetched the zero pins (now
  fixed);
  the Linux integrationTest/e2e and the `.deb` build, and macOS's `devenv test`, need their logs
  for triage.
- 2026-11-04 (portable Linux asset, the user's decision): the Nix Linux asset's ELF `INTERP` and
  `RUNPATH` name `/nix/store` paths (the Nix glibc loader and the source/llama/glibc libraries),
  so the Gradle bootstrap, integrationTest, e2e and the `.deb` cannot start it on a plain host;
  the Nix routes pass because the prebuilt re-link gives them the store paths. The user chose a
  manual `linux-whisper-asset` job on ubuntu-24.04 that builds 1.9.5 with static whisper
  libraries and the system Vulkan, publishing
  `whisper-cpp-1.9.5-x86_64-linux-glibc.tar.gz` under the same tag; `nix/versions.nix`'s Linux
  download points at it and the Nix routes keep the Nix source asset. `desktop/stage.ts` now
  finds and copies the executables instead of `--strip-components=1`, which also fixes the `.deb`
  staging layout. The Linux sha is zero until the user builds/uploads. actionlint, tsc/prettier,
  `nix flake check --no-build --all-systems` and checkVersions green.
- 2026-11-04 (portable Linux sha, user-provided): the release URL
  `…/whisper-cpp-1.9.5/whisper-cpp-1.9.5-x86_64-linux-glibc.tar.gz` serves sha256
  `ffab8189…`; the tar's top level holds `./whisper-cli` and `./whisper-server`, and the ELF
  interpreter is the plain `/lib64/ld-linux-x86-64.so.2` (no Nix store path). `nix/versions.nix`
  and `versions.json` now carry that sha; `gradle checkVersions` green. The sandbox's shell could
  not execute the glibc binary (`./whisper-cli` reported command not found), so the "runs it"
  half remains the user's next full CI run: integrationTest, the e2e journeys and the `.deb`
  build on Linux. User-run, not observable here.
- 2026-11-04 (run 41785198280, id 41785198280, head 8eb45565): the docs check failed because the new manual
  `linux-whisper-asset` job was absent from the gate's `EVENT_GATED` and dispatch-only list; the
  Linux `.deb` and macOS DMG jobs failed at `pnpm --dir desktop test` on `prettier --check .`
  (`stage.ts`). The gating test now classifies the Linux asset job with the other manual assets
  (9/9); `stage.ts` is Prettier-formatted; the desktop typecheck and tests pass except the
  Gradle-wrapper spawn, which this sandbox cannot run (no `/bin/sh`) and CI runs. Commit
  `743ec967`. The next full run must be dispatched on the fixed commit; the old run is stale.
  User-run.
- 2026-11-04 (run 41786262278, id 41786262278, head 48c31cd4, Linux e2e): the fixture test failed on
  `ffmpeg … libpulse.so.0` because `runtimeSystemDeps` (and, on the same prefix check,
  `e2eSystemDeps`) printed `SKIPPED`: both `onlyIf` predicates still tested the old `linux-*`
  node prefix instead of the item's Nix `x86_64-linux`. Both now test `endsWith("-linux")`;
  `gradle checkVersions` green. Commit `2c8a7c13`. The next run must install the libraries and
  re-run the Linux e2e and integration jobs. User-run.
- 2026-11-04 (green run 41787484742, id 41787484742, head 65edc1f9; `8e12f0f3` after it is
  docs-only): the whole matrix passed on the branch — Nix build, devenv
  shell and flake check, bundle and flake-package e2e, Linux/macOS/Windows e2e, Linux/macOS
  integrationTest, the packaged `.deb`, DMG and Windows zip, and the documentation/desktop gates.
  All test-list items are user-verified; the status moves to in-review. The root agent handles the
  squash and the spec move.
- 2026-11-04 (review amendment, test-list item 1): the item's “the same build on every route” was
  wrong for Linux: the Nix routes use the Nix source asset and the Gradle bootstrap/`.deb` the
  portable glibc asset, the two toolchain variants of one version (ADR 0065). Reworded to “one
  version, with the two Linux outputs as toolchain variants”; no other test-list item changed.
