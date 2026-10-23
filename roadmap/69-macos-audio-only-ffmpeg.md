# 69 — An audio-only ffmpeg for macOS aarch64-darwin, shipped as a pinned prebuilt artifact

Status: spec-approved
Depends on: [62 — An audio-only ffmpeg](../docs/specs/audio-only-ffmpeg.md) (done; enable list,
exact-set check and format fixtures), [48 — macOS support](../docs/specs/macos-support.md) (done)
Blocks: —

## Goal

macOS Apple Silicon ships the same audio-only ffmpeg surface as Linux from a pinned prebuilt
artifact, instead of nixpkgs' cached `ffmpeg_8-headless` (Nix path) or bytedeco's full GPL build
(Gradle path and DMG). Intel macOS stays unsupported (README).

## Evidence

- `nix/ffmpeg.nix:198-203`: on darwin the app uses `pkgs.ffmpeg_8-headless` because the audio-only
  recipe is Linux-shaped (static libc) and "its darwin configure is not maintained (roadmap item
  48)". The same file already computes `targetOS = "darwin"` / `targetArch = "aarch64"`
  (`nix/ffmpeg.nix:33-34`) and holds one enable list and one override (`linuxSource`), so only the
  darwin branch is missing. The recorded failure is a one-line claim; its actual configure error is
  not written down anywhere (checked `docs/specs/macos-support.md:233-235`, `docs/known-issues.md`).
- The flake's exact-set check (`flake.nix:92-134`, `nix/ffmpeg-audio-only.sh`) is Linux-shaped: it
  asserts `readelf` reports no `NEEDED` and no `INTERP`, and expects `--enable-static
  --disable-shared`. A darwin binary links libSystem dynamically, so the check needs a darwin
  branch, not only a pin.
- `nix/desktop-artifacts.nix:33-40` pins `ffmpeg.x86_64-linux` only; darwin has no entry, so the
  flake uses the nixpkgs source.
- `versions.json` is the single manifest for the Gradle bootstrap and the DMG: `prepareRuntime`
  downloads `downloads.ffmpeg.<platform>`, and `desktop/stage.ts:113-140` extracts the bytedeco
  **jar** (`jar xf`, classifier directory, chmod for `ffmpeg` **and** `ffprobe`). A `.tar.gz`
  single binary therefore changes `prepareTool`'s ffmpeg branch and `stage.ts`, not only the pin.
- The Gradle path pins bytedeco `ffmpeg-8.1.2-1.5.14-macosx-arm64-gpl.jar`
  (`nix/versions.nix:8-10,51-55`), the full GPL build with `ffprobe`.
- `.github/workflows/ci.yml:312` (`macos-whisper-asset`, manual `workflow_dispatch`) is the macOS
  asset pattern; item 62's surface and exact-set check are the behavior contract.
- Item 48's macOS spec logs the current state (`docs/specs/macos-support.md:233-235`): the devenv
  and server package use nixpkgs' cached `ffmpeg_8-headless`; the Linux pin is unchanged.

## Proposal

1. Reproduce the darwin configure failure on the shared recipe, then make it build for
   `aarch64-darwin` with the same `enable` list (the misleading name `linuxSource` goes). If
   configure cannot be made to work, a bespoke darwin configure lives in the same file and uses the
   same list — decided by the error, not up front.
2. Make the exact-set check OS-aware: on darwin `otool -L` must name only system libraries, with no
   `ffprobe`/`ffplay` and the same component sets; the Linux static assertions stay as they are.
3. Build the binary in a manual `macos-ffmpeg-asset` CI job (like `macos-whisper-asset`), verify it
   with item 62's format fixtures and the exact-set check on macOS, print url and sha256, and the
   user publishes it under the same `desktop-ffmpeg-v2` release (a new tag only if the enable list
   or the nixpkgs pin changes).
4. Add `ffmpeg.aarch64-darwin` to `nix/desktop-artifacts.nix` (url, sha256, the source build's
   storePath and references) and point `versions.json`'s `ffmpeg.darwin-arm64` at the same `.tar.gz`,
   so the flake, `./gradlew` and the DMG ship the same bytes. `darwin-x64` stays untouched
   (unsupported Intel macOS).
5. Adapt the consumers to a single-binary archive: `prepareTool`'s ffmpeg branch (no jar, no
   library-path wrapper on darwin) and `desktop/stage.ts` (tar extraction, no classifier directory,
   no `ffprobe`).
6. Verify the packaged macOS app's audio path with the existing fixtures and the journey e2e.

## Decided (consultation, 2026-10-22)

- **The shared recipe gets a darwin build** (the user): one enable list and one override serve both
  systems; the first cycle reproduces the recorded configure failure and fixes it. A bespoke darwin
  configure is the fallback only if the error cannot be fixed in the shared one.
- **The Gradle bootstrap and the DMG move to the same asset** (the user): `versions.json`'s
  `ffmpeg.darwin-arm64` points at the pinned `.tar.gz`, so the shipped DMG drops the full GPL build
  and `ffprobe`. This edits `desktop/stage.ts` and `prepareTool`; it is accepted as part of the
  item, not deferred.
- **aarch64 only** (the user): matches the shipped DMG and the pinned nixpkgs (no x86_64-darwin);
  the stale `darwin-x64` bytedeco entry stays unsupported data.
- **`.tar.gz`** (the user): the Gradle bootstrap already extracts it (whisper-cli) and a stock macOS
  host has no zstd; the artifact helper supports it.
- **Test list** approved as worded below.
- **No new ADR**: these are implementation choices inside item 62's recorded artifact model; they
  are recorded here, like item 62's own decisions.

## Open questions

None. (All four settled 2026-10-22, see "Decided (consultation, 2026-10-22)".)

## Test list (approved; behaviors only)

- [ ] The manual `macos-ffmpeg-asset` job builds `aarch64-darwin` with the shared enable list, and
      the exact-set check passes on darwin: `otool -L` names only system libraries, no
      `ffprobe`/`ffplay`, and the configure line's component sets equal `nix/ffmpeg.nix`'s lists
- [ ] The committed per-extension fixtures decode/encode through the app's surface with the darwin
      binary (`f32le` decode, `silencedetect`, `atrim`, FLAC encode, `concat`), with the same
      result as item 62's Linux check
- [ ] On darwin, `nix build .`, `nix run .#desktop` and the devenv shell use the pinned artifact —
      no ffmpeg source build in the evaluation/dry-run — and `nix flake check` passes, the
      asset-pin check included
- [ ] The installed DMG transcribes an upload end-to-end and its `ffmpeg -version` is the audio-only
      build (no bytedeco GPL build, no `ffprobe` staged)
- [ ] `./gradlew prepareRuntime` on macOS downloads the pinned asset, sha256-verifies it and
      resolves `ffmpeg`; `./gradlew e2e` stays green
- [ ] A mismatched pin (wrong sha256 or storePath, or the old tag) fails the asset-pin check naming
      the artifact and its url
- [ ] (must not break) The Linux asset/pin, Linux e2e, `nix flake check` and the macOS packaged e2e
      stay green

## Acceptance criteria

- macOS Apple Silicon gets the audio-only ffmpeg from a pinned prebuilt artifact on every path
  (flake, devenv, `./gradlew`, DMG); no source build of ffmpeg is needed on darwin, and the
  package's binary has only system library dependencies.
- The shared enable list and the exact-set check cover both systems; the pin is verified by the
  existing asset-pin mechanism.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-22: filed by the workspace agent at the user's request; not yet discussed.
- 2026-10-22: copied to `main` from `workspaces/49-windows-support` at the user's request; the
  evidence was re-checked against `main`. Not yet discussed.
- 2026-10-22: consulted (main tree). The user chose the shared recipe, the same asset for the Gradle
  path and the DMG, aarch64 only and `.tar.gz`, and approved the test list. Status: spec-approved.
