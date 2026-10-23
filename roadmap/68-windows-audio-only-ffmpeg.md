# 68 — An audio-only ffmpeg for Windows, shipped as a pinned prebuilt artifact

Status: spec-approved
Depends on: [62 — An audio-only ffmpeg](../docs/specs/audio-only-ffmpeg.md) (done; its enable list,
exact-set check and format fixtures), [49 — Windows support](49-windows-support.md) (the Windows
tool pins, bootstrap, packaging and its e2e leg)
Blocks: —

## Goal

The Windows deliverable ships an ffmpeg built for exactly what the backend does — decode an
upload's audio track, `silencedetect`/`atrim`, the canonical FLAC encode and the `concat` backfill —
as a pinned prebuilt artifact, like the Linux desktop's `desktop-ffmpeg-v2`. Today Windows uses
bytedeco's full GPL build: `ffmpeg.exe`, `ffprobe.exe` (which nothing runs) and every `av*` DLL,
including video, capture, network and hardware-acceleration code the backend never invokes.

## Evidence

- Windows is not supported on `main` yet (item 49, in-progress). Item 49's branch pins bytedeco
  `ffmpeg-8.1.2-1.5.14-windows-x86_64-gpl.jar` from Maven Central (`nix/versions.nix`, its
  `windows-x64` block) and downloads it in `build.gradle.kts`, noting the jar carries `ffmpeg` and
  `ffprobe`.
- Item 49's branch guards the manifest with `verifyWindowsPins` (`build.gradle.kts:60-89`): it
  hard-requires the asset name `windows-x86_64-gpl.jar` for the `windows-x64` ffmpeg pin. Moving
  the pin means changing this guard in the same change, or the Gradle check fails on every host.
- The backend's ffmpeg surface is `docs/specs/audio-only-ffmpeg.md` § Evidence/§ Decided (item 62):
  `AudioDecoder` (`-ac 1 -ar 16000 -f f32le`), `AudioChunker` (`silencedetect`, FLAC
  `-frame_size 4000`), `WindowCutter` (`atrim`, FLAC) and V10's `concat` backfill. Nothing else is
  called.
- Item 62's Linux artifact is one static binary (`nix/desktop-artifacts.nix:33-40`); item 63's
  whisper-cpp and item 49's `windows-whisper-asset` CI job (`.github/workflows/ci.yml`, item 49's
  branch) show the pinned-asset shape for Windows: a manual `windows-2025` job builds, verifies,
  uploads the archive and prints its sha256; the user publishes it.
- The Gradle bootstrap extracts `.tar.gz` and `.jar` (`build.gradle.kts:244-245` on `main`); stock
  Windows has `tar` but no `zstd`, so a Windows artifact must be `.tar.gz` or `.zip`, unlike the
  Linux `.tar.zst`.
- Item 49's packaged zip/MSI stage the managed tools from `versions.json` (its test-list items for
  the portable zip and the MSI; both still open); without this item they carry the full bytedeco
  build.
- Item 62's license surface comes from the enable list (`withGPL = false`, `withVersion3 = false`,
  `docs/specs/audio-only-ffmpeg.md` § Decided): the same list on Windows has the same LGPL surface;
  the asset job can assert the configuration line carries no `--enable-gpl`/`--enable-version3`.
- If item 69 lands first, `desktop/stage.ts` and `prepareTool` already extract a `.tar.gz` tool for
  darwin; the Windows path reuses that instead of adding a second extractor.

## Proposal

1. Build ffmpeg 8.1.2 on Windows with item 62's exact `enable` list (`--disable-everything`, no
   `ffprobe`/`ffplay`, no GPL/version3) in a manual `windows-ffmpeg-asset` CI job on
   `windows-2025`, using MSYS2's mingw-w64 gcc (the standard ffmpeg-on-Windows toolchain) and a
   static build (`--enable-static --disable-shared`, `-static`) that yields one `ffmpeg.exe` with
   only system DLL imports.
2. Verify the artifact in that job with item 62's format fixtures, the exact-set check, the DLL-import
   check and the absence of `ffprobe`; pack it as `.tar.gz`, print url and sha256, and the user
   publishes it as another asset of the same `desktop-ffmpeg-v2` release (a new tag only if the
   enable list or the nixpkgs pin changes).
3. The user's pin goes into `nix/versions.nix` / `versions.json` as `ffmpeg.windows-x64` (a
   `.tar.gz`), and `verifyWindowsPins` learns the new asset name.
4. `prepareTool` / `desktop/stage.ts` handle the single-binary archive on Windows (extract the
   `.tar.gz`, resolve `ffmpeg.exe`, no jar classifier directory, no `ffprobe`); item 49's zip/MSI
   then stage it instead of the bytedeco jar.
5. Verify the packaged Windows app's audio path with the existing fixtures and item 49's e2e
   journey.

## Decided (consultation, 2026-10-22)

- **MSYS2 mingw-w64 gcc** (the user): the standard ffmpeg-on-Windows route via
  `msys2/setup-msys2`; no measured performance reason favors MSVC, whose configure path still needs
  an MSYS2 shell and uses the static CRT. The compiler version follows the action/runner image,
  like item 63's job.
- **One static `ffmpeg.exe`** (the user): mirrors the Linux and macOS assets — one file to pin and
  stage, no DLL search path beside the app.
- **`.tar.gz`** (the user): the bootstrap already extracts it and stock Windows has no zstd.
- **The same `desktop-ffmpeg-v2` release** (the user): one asset per platform, like item 63's
  `desktop-whisper-cpp-v1`; the enable list and nixpkgs pin do not change.
- **The Gradle guard moves with the pin**: `verifyWindowsPins`'s hard-coded asset name changes in
  the pin cycle, so no host can validate a manifest the bootstrap cannot use.
- **License stays item 62's** (the user, in the same consultation): the identical enable list, no
  GPL/version3 components; the asset job asserts their absence rather than adding a new decision.
- **Test list** approved as worded below.
- **No new ADR**: these are implementation choices inside item 62's recorded artifact model.
- Implementation starts only after item 49 merges (its pins, packaging and Windows e2e leg are what
  this item changes and verifies).

## Open questions

None. (All four settled 2026-10-22, see "Decided (consultation, 2026-10-22)".)

## Test list (approved; behaviors only)

- [ ] The manual `windows-ffmpeg-asset` job builds `windows-x64` with the shared enable list, and
      the exact-set check passes: the configure line equals `nix/ffmpeg.nix`'s component sets, no
      `ffprobe`/`ffplay`, no `--enable-gpl`/`--enable-version3`, and the exe imports only system
      DLLs (no VC runtime, no non-system libraries)
- [ ] The committed per-extension fixtures decode/encode through the app's surface with the Windows
      binary (`f32le`, `silencedetect`, `atrim`, FLAC, `concat`), with the same result as item 62's
      Linux check
- [ ] `./gradlew prepareRuntime` on Windows downloads the pinned `.tar.gz`, sha256-verifies it and
      resolves `ffmpeg.exe`; the Windows e2e leg stays green
- [ ] The packaged Windows artifact (portable zip and MSI) ships the audio-only exe — no bytedeco
      jar, no `ffprobe` — and transcribes an upload end-to-end
- [ ] `verifyWindowsPins` accepts the new asset name and fails on a wrong asset, url or sha256; the
      asset-pin check names the pinned tag
- [ ] (must not break) The Linux and macOS pins, `nix flake check` and the Linux e2e stay green

## Acceptance criteria

- Windows gets the audio-only ffmpeg from a pinned prebuilt artifact on every path (`./gradlew`,
  portable zip, MSI); the binary is one static `ffmpeg.exe` with only system DLL imports and no
  `ffprobe`, built from the same enable list as Linux and macOS.
- The manifest guard and the packaging stage handle the archive, and the pin is verified by the
  existing asset-pin mechanism.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-22: filed by the workspace agent at the user's request; not yet discussed.
- 2026-10-22: copied to `main` from `workspaces/49-windows-support` at the user's request. The
  Windows-specific evidence and line numbers name item 49's branch until it merges; everything
  other than Windows was re-checked against `main`. Not yet discussed.
- 2026-10-22: consulted (main tree). The user chose MSYS2 mingw-w64 gcc (after a gcc-vs-MSVC
  performance question: SIMD dominates, no measured reason for MSVC), one static exe, `.tar.gz`
  and the same release, and approved the test list. Status: spec-approved; work starts after item
  49 merges.
