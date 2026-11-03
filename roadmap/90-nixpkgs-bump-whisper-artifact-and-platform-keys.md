# 90 — A nixpkgs bump, our own whisper.cpp 1.9.5 artifact on every route, and one platform vocabulary

Status: spec-approved
Depends on: [63](../docs/specs/whisper-cpp-artifact.md) (the pinned-artifact mechanism), [48](../docs/specs/macos-support.md) and [49](../docs/specs/windows-support.md) (the darwin and Windows assets), [33](../docs/specs/gradle-bootstrap.md) (the bootstrap that downloads them)
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
  The other families keep their `desktop-ffmpeg-vN` / `desktop-mesa-vN` tags: their content changes
  with our configure lists while the upstream version stays fixed (the ffmpeg v2 rework is the
  precedent), so a version-named tag would run out of names there.
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
  route (no third-party prebuilds), platform keys are Nix system names, and a release tag names what
  determines the artifact's content — the whisper.cpp version for whisper (`whisper-cpp-1.9.5`,
  with a `-2`-style suffix only for a same-version rebuild), the internal `vN` counter where our own
  configure lists change the content at a fixed upstream version.
- `whisper-cpp-artifact`, `windows-support`, `macos-support`, `gradle-bootstrap` and
  `ci-verification` are updated; README's supported-platform paragraph already states the Intel
  outcome.

## Open questions

None — the four decisions above are the user's (2026-11-03); the archive format, the deletion of the
jiang1997 entries and the same-version-rebuild suffix follow from them.

## Test list

- [ ] (basic) The dev shell, `nix build .`, `.#desktop`, `.#desktop-bundle` and the Docker image all
      ship whisper-cli 1.9.5 from the pinned asset (the same build on every route), and `whisper-cli`
      starts and reports its help on each.
- [ ] (basic) The Gradle bootstrap on Linux, Windows and Apple Silicon downloads whisper-cli 1.9.5
      from our release asset (the manifest's URL), verifies its sha256 and runs it.
- [ ] (basic) The Windows MSI/zip and the macOS DMG install a whisper-cli that transcribes the
      fixture on a machine with no development tools.
- [ ] (basic) Transcribing the fixture with 1.9.5 yields the transcript the app shows today: the
      backend's integration transcription scenarios produce their expected turns and text, and the
      e2e transcript journey's expected phrase still appears.
- [ ] (edge) The whisper assets' release tag, file names and pin URL all carry the manifest's
      whisper.cpp version (`whisper-cpp-1.9.5`); bumping the manifest version moves the tag, the
      names and the pin together, and a pin whose tag names another version fails the check.
- [ ] (edge) The Windows asset runs on a stock Windows install (static CRT, the pinned Vulkan loader
      beside the executables) and uses a GPU when a Vulkan driver is present, the CPU otherwise.
- [ ] (edge) The macOS arm64 asset starts whisper-cli and the app log carries the Metal backend line.
- [ ] (edge) A stale pin still fails at evaluation, naming the asset, the url, and both store paths
      (the asset built from another whisper.cpp build than the flake's).
- [ ] (edge) `checkVersions` passes on the bumped pin (nixpkgs rev, wrapper, Rust), and
      `node e2e/check-playwright.ts` passes with the new Playwright version and browser revision.
- [ ] (edge) An unsupported platform (an Intel Mac, Windows on ARM64) fails fast, naming the
      supported platforms, instead of resolving a missing download.
- [ ] (failure) A downloads table with a key that is not a Nix double fails the platform check, as
      does a supported system without a pin for every downloaded tool.
- [ ] (must not break) The packaged and unpackaged e2e journeys stay green on Linux, Windows and
      macOS, including the packaged Windows MSI and zip jobs.
- [ ] (must not break) `./gradlew prepareRuntime` on a second run reuses the cached downloads and
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
