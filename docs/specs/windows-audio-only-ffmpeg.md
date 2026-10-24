# 68 — An audio-only ffmpeg for Windows, shipped as a pinned prebuilt artifact

Status: done (roadmap item 68, approved 2026-10-24)
Depends on: [62 — An audio-only ffmpeg](audio-only-ffmpeg.md) (done; its enable list,
exact-set check and format fixtures), [49 — Windows support](windows-support.md) (the Windows
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

- [x] The manual `windows-ffmpeg-asset` job builds `windows-x64` with the shared enable list, and
      the exact-set check passes: the configure line equals `nix/ffmpeg.nix`'s component sets, no
      `ffprobe`/`ffplay`, no `--enable-gpl`/`--enable-version3`, and the exe imports only system
      DLLs (no VC runtime, no non-system libraries)
- [x] The committed per-extension fixtures decode/encode through the app's surface with the Windows
      binary (`f32le`, `silencedetect`, `atrim`, FLAC, `concat`), with the same result as item 62's
      Linux check
- [x] `./gradlew prepareRuntime` on Windows downloads the pinned `.tar.gz`, sha256-verifies it and
      resolves `ffmpeg.exe`; the Windows e2e leg stays green
- [x] The packaged Windows artifact (portable zip and MSI) ships the audio-only exe — no bytedeco
      jar, no `ffprobe` — and transcribes an upload end-to-end
- [x] `verifyWindowsPins` accepts the new asset name and fails on a wrong asset, url or sha256; the
      asset-pin check names the pinned tag
- [x] (must not break) The Linux and macOS pins, `nix flake check` and the Linux e2e stay green

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

- 2026-10-24: cycle 1 (test-list item 1) green on the Windows runner. The shared component lists
  moved to `nix/ffmpeg-enable.json` (read by `nix/ffmpeg.nix`, by flake.nix's expected-surface
  rendering and by the new job), and `windows-ffmpeg-asset` builds ffmpeg 8.1.2 with MSYS2's
  mingw-w64 gcc, proves the surface and packages `bin/ffmpeg.exe`. Red runs: `e94ccfe`
  (`ERROR: pthreads requested but not found` → `--disable-pthreads --enable-w32threads`) and
  `ee326b31` (`curl: (35) Recv failure: Connection reset by peer` → curl retries). Green at
  `3af00e73` (run 40094395175, job 120873691774, every step success). The user published
  `ffmpeg-audio-only-8.1.2-windows-x64.tar.gz` under the `desktop-ffmpeg-v2` prerelease; sha256
  `99fc0986b1a1fe5719349d13c371e5b57b8b5ebbff596c111b70146fd04bc1a9` (the release digest and a
  fresh local download agree). Independently re-checked here: the tar holds only `bin/ffmpeg.exe`
  (4,427,264 B, PE32+ x86-64, stripped); its imports are KERNEL32.dll, SHELL32.dll, bcrypt.dll and
  msvcrt.dll (no VC runtime, no library beside it); and its embedded `configuration:` line equals
  `nix/ffmpeg-enable.json` exactly (7 kinds, 58 decoders, no `--enable-gpl`/`--enable-version3`).
  `nix flake check` over the JSON move: green (9m35s; the Linux checks, `ffmpeg-closure-slim`
  included; `aarch64-darwin` is not checkable from the Linux host and Nix said so).

- 2026-10-24: cycle 2 (test-list item 2) green on the Windows runner, run 40096476640
  (`2cf9fe92`), job 120879725628: every step success. Red first: the proof's concat step failed with
  `Impossible to open 'D:/tmp/app-uses/one.flac'` — `nix/ffmpeg-app-uses.sh` wrote absolute POSIX
  paths into the concat list, which `ffmpeg.exe` reads as a drive-relative path. The app itself
  writes bare file names relative to the list (`V10__BackfillCanonicalClip.kt:81`), so the script
  now matches the app; local rerun green. The same run's build logged the configure summary
  (static, w32threads, LGPL 2.1, no external libraries) and the import proof
  (`bcrypt.dll KERNEL32.dll msvcrt.dll SHELL32.dll`). The macOS asset job in that run was green too.

- 2026-10-24: cycle 3 (test-list item 5) green. The pin is
  `ffmpeg.windows-x64` = the `desktop-ffmpeg-v2` asset `ffmpeg-audio-only-8.1.2-windows-x64.tar.gz`
  (2,074,086 B), sha256 `983118c378778f542c3190e7ecac84e02ff5d7d2dd05bce4c199e30f5a30a001` — the
  release digest and a fresh local download agree; the exe inside is the run-40096476640 build
  (imports KERNEL32/SHELL32/bcrypt/msvcrt, configure line equal to `nix/ffmpeg-enable.json`).
  `verifyWindowsPins` names the release tag and asset for ffmpeg and whisper-cli. `checkVersions`
  green with the pin; red naming the pin for a wrong tag, a wrong asset name, and a non-64-hex
  sha256 (a well-formed wrong sha256 is caught by the download's verification, item 3).
  The asset first published in cycle 1 (sha256 `99fc0986…`, run 40094395175) was replaced under the
  same name by the run-40096476640 build before the pin was recorded; no pin ever named the first
  upload. The job's builds are not byte-reproducible (run 40099483165 built yet another digest), so
  the pinned bytes are re-proven by the surface and import checks, not rebuilt identically.

- 2026-10-24: cycles 4 and 5 (test-list items 3 and 4) green together on the Windows runner, run
  40099483165 (`4633bc81`), job 120889367398 `Windows (packaged zip e2e)`, every step success:
  `prepareRuntime` downloaded the pinned `.tar.gz`, sha256-verified it and resolved `ffmpeg.exe`;
  `dist:win` built the portable zip and the MSI; the unpacked zip passed specs 01-04 (01 is the
  upload-to-transcription journey) and the installed MSI passed the same specs plus the MSI
  lifecycle spec. Red for item 4 was recorded locally on the pinned tar with the operation the old
  `stage.ts` performed: `jar xf` → `java.util.zip.ZipException: zip END header not found`;
  `stage.ts` now shares the single-binary `.tar.gz` branch between Apple Silicon and Windows
  (`ffmpeg.exe`, no classifier directory, no `ffprobe`), Linux keeps the bytedeco jar. The same run
  also had `Linux (Nix flake check)`, `Linux (packaged .deb e2e)` and both macOS asset jobs green.

- 2026-10-24: cycle 6 (test-list item 6) ticked from the same run 40099483165: `Linux (Nix flake
  check)` (the `versions-sync` check covers the pinned `versions.nix`/`versions.json` pair),
  `Linux (e2e)`, `Linux (packaged .deb e2e)`, `Linux (bundle e2e)`, both macOS asset jobs,
  `macOS (Nix build + devenv)` and `macOS (DMG + packaged e2e)` all green — the Linux and macOS
  pins are unchanged and keep passing. One job in that run, `macOS (e2e)`, failed outside this
  item's criteria (it runs the nixpkgs headless ffmpeg, not the audio-only artifact); the rerun is
  pending, and if it repeats its failing spec belongs in `docs/known-issues.md`.
- 2026-10-24: test list complete; `Status: in-review`.
- 2026-10-24 (review, root agent; decisions by the user): approved. Independently re-checked the
  pinned asset: sha256 and size as pinned, only `bin/ffmpeg.exe`, imports `bcrypt.dll`,
  `KERNEL32.dll`, `msvcrt.dll` and `SHELL32.dll`, and an embedded configure line carrying every
  `nix/ffmpeg-enable.json` component flag with no GPL/version3/external-library flag; the JSON
  lists equal the former `nix/ffmpeg.nix` lists in order. The `MINGW64` deprecation went to
  `docs/known-issues.md`. The run's `macOS (e2e)` failure is left to the pull request's CI. Status:
  done (roadmap item 68).
