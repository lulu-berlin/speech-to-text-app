# 69 — An audio-only ffmpeg for macOS aarch64-darwin, shipped as a pinned prebuilt artifact

Status: done (roadmap item 69, approved 2026-10-23)
Depends on: [62 — An audio-only ffmpeg](audio-only-ffmpeg.md) (done; enable list,
exact-set check and format fixtures), [48 — macOS support](macos-support.md) (done)
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

- [x] The manual `macos-ffmpeg-asset` job builds `aarch64-darwin` with the shared enable list, and
      the exact-set check passes on darwin: `otool -L` names only system libraries, no
      `ffprobe`/`ffplay`, and the configure line's component sets equal `nix/ffmpeg.nix`'s lists
- [x] The committed per-extension fixtures decode/encode through the app's surface with the darwin
      binary (`f32le` decode, `silencedetect`, `atrim`, FLAC encode, `concat`), with the same
      result as item 62's Linux check
- [x] On darwin, `nix build .`, `nix run .#desktop` and the devenv shell use the pinned artifact —
      no ffmpeg source build in the evaluation/dry-run — and `nix flake check` passes, the
      asset-pin check included
- [x] (discovered, confirmed 2026-10-23) `nix run .#desktop` does not exist on darwin: the Nix
      desktop package is Linux-only (the macOS app is the electron-builder DMG, item 4). The darwin
      flake path is proved instead through the default package's closure
      (`ffmpeg-audio-only-everywhere`), the `ffmpeg-asset-pin` app and an assertion that the devenv
      shell's `ffmpeg` is the flake's ffmpeg and not the source build
- [x] The installed DMG transcribes an upload end-to-end and its `ffmpeg -version` is the audio-only
      build (no bytedeco GPL build, no `ffprobe` staged)
- [x] `./gradlew prepareRuntime` on macOS downloads the pinned asset, sha256-verifies it and
      resolves `ffmpeg`; `./gradlew e2e` stays green
- [x] (discovered, confirmed 2026-10-23) The e2e fixture generator uses only the app's ffmpeg
      surface: it decodes the pinned sample and the LibriSpeech clips to `f32le` (the app's own
      decoder args) and cuts/loops/concatenates them as 16 kHz mono PCM WAV in Node, so the
      managed slim darwin binary is enough and `./gradlew e2e` needs no general-purpose ffmpeg;
      the README says so
- [x] A mismatched pin (wrong sha256 or storePath, or the old tag) fails the asset-pin check naming
      the artifact and its url
- [x] (discovered, confirmed 2026-10-23) The ffmpeg artifact's integrity gates also hold on darwin:
      a registered asset that is missing or does not match its sha256 fails naming the asset, url
      and sha256; a pin made from another source build fails naming both store paths; a stray
      store path is rejected; and the real darwin prebuilt names no old build path or stray path
      and sits in its own runtime closure
- [x] (must not break) The Linux asset/pin, Linux e2e, `nix flake check` and the macOS packaged e2e
      stay green
- [x] (discovered, 2026-10-23, from the root review) The manual `macos-ffmpeg-asset` job always
      builds, packages and prints the darwin asset and its pin, even when the recorded pin is
      stale; its fixture and app-command proofs run against the job's own build; and the pin proof
      still fails the job, after packaging, when the recorded pin does not match the built asset
- [x] (discovered, 2026-10-23, from the root review) A pin whose url names another file than the
      one this build gives its asset (`.tar.zst` on Linux, `.tar.gz` on darwin) fails the asset-pin
      check at evaluation, naming the expected file and the url
- [x] (discovered, 2026-10-23, from the root review) The fixture generator's WAV header is
      consistent: the RIFF size is the file size minus 8, the byte rate is sample rate × block
      align, and the block align is channels × bytes per sample

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

- 2026-10-23 (cycle 1, test-list item 1; CI-only macOS): the user has no macOS machine, so every
  macOS run is a `workflow_dispatch` with `assets: true` on the branch — the manual asset jobs are
  opt-in through the workflow's new `assets` input (item 49's pattern). Red at `800d08fd`: the
  shared recipe's darwin configure found no compiler (`gcc is unable to create an executable file.`
  … `C compiler test failed.`), because our flag list replaces nixpkgs', which is where a clang
  stdenv's `--cc=clang`/`--cxx=clang++` live. Green at `b311e454` (`macOS ffmpeg asset (manual)`,
  macOS 15 arm64): `ffmpeg-audio-only: ok` — one binary, `otool -L` names only the OS's libraries,
  no ffprobe/ffplay, and the configure line's component sets equal `nix/ffmpeg.nix`'s lists. The job
  packaged `ffmpeg-audio-only-8.1.2-aarch64-darwin.tar.gz` and printed the pin: url
  `…/desktop-ffmpeg-v2/ffmpeg-audio-only-8.1.2-aarch64-darwin.tar.gz`, sha256
  `9f0c576d370ed47da5a541f7021e21a8a6916c2949a5546cbab7d08528a2b313`, storePath
  `/nix/store/2y34dvlvmkxrbiq0kqq3wdx1m5mvkkjk-ffmpeg-audio-only-8.1.2`, references `[]`; the tar
  is the run's `ffmpeg-audio-only-aarch64-darwin` artifact. A harness bug found on the way (`find
  result` does not follow the output symlink) is fixed in the same commit. The Linux
  build kept its exact flags and out path (`2pqr…`, the Linux `desktop-ffmpeg-v2` pin);
  `nix flake check --no-build --all-systems` and `actionlint` are green. Item 1 ticked; the user
  publishes the tar under `desktop-ffmpeg-v2` and its pin is added in item 3.

- 2026-10-23 (cycle 2, test-list item 2; CI-only macOS): red at `20e96b7f` before the checks were
  enabled — the new `Prove the fixtures and the app's commands on darwin` step failed with
  `flake … does not provide attribute 'checks.aarch64-darwin.ffmpeg-upload-formats'`. Green in the
  same job: `ffmpeg-upload-formats` (all 19 `allowed-extensions.json` fixtures plus the four
  variants decoded to the canonical FLAC and validated from STREAMINFO) and `ffmpeg-app-uses`
  (f32le decode of the webm's audio track, `silencedetect` to `pipe:`, the `atrim` window at 3200
  samples, the V10 `concat` backfill at 16000) both pass on macOS 15 arm64. They are no longer
  excluded on darwin, so `macos-nix`'s `nix flake check` runs them too; the scripts need only the
  coreutils their checks already provide. The pin is unchanged (`9f0c576d…`, storePath
  `2y34dvlv…`, references `[]`) and the tar is uploaded again. Item 2 ticked.

- 2026-10-23 (cycle 3, test-list item 3; CI-only macOS): red by evaluation, before the pin —
  `ffmpeg-audio-only-everywhere: nix/desktop-artifacts.nix has no ffmpeg.aarch64-darwin entry, so
  the ffmpeg is a source build` and `ffmpeg-asset-pin: … has no ffmpeg.aarch64-darwin entry, so
  there is no pin to verify`; the shell assertion's pre-pin state is `shipped == source`. The user
  published the run-2 tar (sha256 `9f0c576d…`). Green at `535797d1` (CI run
  [39880624864](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39880624864), all 20
  jobs): `macOS ffmpeg asset (manual)` printed `ffmpeg-asset-pin: ok` before packaging, and
  `macOS (Nix build + devenv)` passed the shell assertion and `nix flake check`, which on darwin
  now runs `ffmpeg-audio-only-everywhere` (the default package's closure holds the artifact and not
  the source), `ffmpeg-upload-formats` and `ffmpeg-app-uses`; Linux's `Nix (flake check)` and
  `Nix (build)` stayed green. The pin is `ffmpeg.aarch64-darwin` = the published `.tar.gz`
  (`storePath 2y34dvlv…`, references `[]`), so darwin resolves `ffmpeg` to the re-link output
  `ksr41flk…` while the source build is never built. The branch was then rebased onto `main`
  `7664d42b` with `--onto` (only the item commit replayed, so main's roadmap documents for items
  68 and 71–75, `README` and `AGENTS.md` are untouched). Items 3 and the discovered scope item
  ticked.

- 2026-10-23 (cycle 4, test-list item 7; after the sandbox crash, a new workspace agent): the
  generator, already rewritten on the branch without a recorded red, moved from
  `e2e/global-setup.mjs` into `e2e/lib/fixtures.ts` (`writeFixtures`, TypeScript per ADR 0054) so
  a test can run it. The test `e2e/lib/fixtures.test.ts` (node:test, in the e2e package's `test`
  script and its `tsc` include) writes the fixtures with whichever ffmpeg is first on `PATH` and
  asserts every checked clip is 16 kHz mono 16-bit PCM WAV of the expected length — the full
  sample as long as `jfk.wav`'s own header says (176000 samples), the 3–10 s and 9–11 s trims
  112000 and 32000, the 12-copy loop 12× the sample, the LibriSpeech merge 106000 + 119200 +
  123760 (their FLAC STREAMINFO totals) — and that all fixtures have distinct bytes. Red with the
  devenv shell's slim static ffmpeg (same enable list as the darwin build) and `main`'s generator
  (`b759ec01`) in its place: `ffmpeg -y -loglevel error -t 6 -i …/jfk.wav …/jfk-b.wav` →
  `Unable to choose an output format for '…/jfk-b.wav'` (exit 234). Green with the branch's
  generator: the test and `processes.test.mjs` pass, `tsc -p desktop/tsconfig.json` and
  `prettier --check .` are clean. On macOS, CI run
  [39886710824](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39886710824)
  (`6d822a1b`, the same generator before the move) built every fixture with the managed slim
  darwin binary in `macOS (e2e)` (41 passed). The Linux `E2E (Playwright)` job runs the same
  generator with bytedeco's full build, so it proves the generator, not the slim surface; the DMG
  job does not run it at all (corrected 2026-10-23 after the root review, see below). The README
  names the module and the test. Item 7 ticked.
  Refactors approved by the user: the test compares the sample's format by destructuring
  (`82bf029c`), and the fixture loops' comments in `fixtures.ts` and `backend.mjs` give the real
  copy counts and durations (`465e7100`). Discovered and not added (the user, 2026-10-23): Intel
  macOS stays unsupported, so `stage.ts` staging the bytedeco jar on `darwin-x64` is not a
  scenario; the generator's PCM16 re-encoding is not lossless against `jfk.wav`, which does not
  matter.

- 2026-10-23 (cycle 5, test-list item 6; CI-only macOS): red by argument, as agreed with the user —
  on `main` (`b759ec01`) `versions.json`'s `ffmpeg.darwin-arm64` is the bytedeco GPL jar, so
  `prepareRuntime` resolves that build and not the pinned asset; with only the pin switched,
  `./gradlew e2e` fails in global setup at the first trim, because the darwin build has the same
  component sets as the Linux one (item 1's exact-set check) and item 7's red shows that build
  has no `wav` muxer. Green in CI run
  [39886710824](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39886710824)
  (`6d822a1b`), `macOS (e2e)`: `> Task :prepareRuntime` ran with the downloads cache restored
  (key `stt-downloads-macOS-a6a7…`, from the pins); `downloadVerified` hashes a cached archive on
  every run and logs `Downloading …` or a mismatch warning otherwise, and neither appeared, so the
  cached `9f0c576d370e-ffmpeg-audio-only-8.1.2-aarch64-darwin.tar.gz` matched the pin — the user
  accepted this verified-from-cache evidence for "downloads". `appEnv` puts the extracted
  binary's directory first on `PATH`, the suite ran 41 tests green and the Metal assertion passed;
  the DMG job's `prepareRuntime` and `stage: ffmpeg verified from cache (…/9f0c576d370e-…tar.gz)`
  agree. Item 6 ticked.
  Refactors approved by the user (comments only, `ktlintCheck` green): `prepareTool`'s archive
  comment names the darwin `.tar.gz` (`c26a3f9f`), and the wrapper comment says why neither macOS
  ffmpeg needs one (`c69987c5`).

- 2026-10-23 (cycle 6, test-list item 5; CI-only macOS): the test is `macos-dmg`'s step "Assert the
  packaged ffmpeg is the audio-only build" (the installed app's `Resources/tools/bin` holds an
  executable `ffmpeg`, no `ffprobe`, a configure line with `--disable-everything` and no
  `--enable-lib*`), followed by the packaged specs. Red at `9f2f5505`, which kept the step and put
  back `main`'s darwin ffmpeg (`versions.json`, `nix/versions.nix`, `desktop/stage.ts`): CI run
  [39893252843](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39893252843)
  staged `ffmpeg-8.1.2-1.5.14-macosx-arm64-gpl.jar` (`stage: downloading …`), built and installed
  the DMG, and the step failed with `the packaged tools still ship ffprobe` (exit 1), skipping the
  packaged specs. Green by reverting that commit — the three files are then identical to
  `6d822a1b`'s — with CI run
  [39886710824](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39886710824) on
  `6d822a1b`: `stage: ffmpeg verified from cache (…/9f0c576d370e-ffmpeg-audio-only-8.1.2-aarch64-darwin.tar.gz)`,
  the step printed `packaged ffmpeg: ffmpeg version 8.1.2 Copyright (c) 2000-2026 the FFmpeg
  developers`, and all four packaged specs passed, the journey (`the packaged desktop app
  transcribes an upload and shows it in History, in its own window`) included. Item 5 ticked.
  Refactors approved by the user: the assertion's comment names test-list item 5 (`5ae68cde`),
  `stage.ts`'s header says the Apple Silicon ffmpeg links only system libraries (`208634cf`), and
  the assertion runs the packaged `ffmpeg -version` once (`e3eca2ef`). No scenarios discovered.

- 2026-10-23 (cycle 7, test-list item 8): the user chose a flake check over a CI-only step, with
  nixpkgs' shell helpers and no hand-written shebangs. The test is `ffmpeg-asset-pin-mismatch`
  (both systems): fixtures stand in for the source build and the asset, so nothing is compiled; a
  wrong `storePath` and the old tag (`desktop-ffmpeg-v1`) must fail at evaluation, and a sha256
  that is not the asset's must fail the check's own verification, each naming the asset file and
  the pin's url. Red (local `nix eval`, x86_64-linux and aarch64-darwin): the asset-pin check
  threw `ffmpeg-asset-pin: ffmpeg.aarch64-darwin.storePath is /nix/store/0000…-ffmpeg-audio-only-8.1.2,
  not this build's …` — no file, no url — and its tag (`the pin's url is not on the
  desktop-ffmpeg-v2 release`) and sha256 (`the pin's sha256 is not the asset's …`) messages name
  neither. Green: `nix/asset-pin-check.nix` builds its pin gates as `pinError` (thrown by `check`
  and `app`) and its sha256 gate as `verifyAsset` (`writeShellApplication`), every message naming
  `<file> (<url>)`; the "the pin's url does not name <file>" gate was dropped as tautological
  (wrong: it was live until `file` came from the pin's url — corrected in cycle 10, the gate is back
  against the name the build gives its asset). Locally the check evaluates on both systems, the three messages
  read as required, the `verifyAsset` script fails on a wrong sha256 and on a missing asset, and
  `nix flake check --no-build --all-systems` passes; this sandbox's chroot store cannot build, so
  the check's build (the sha256 case) waits for CI's `nix flake check` on Linux and macOS. Item 8
  stays unticked until that run is reported.
  CI run [39895208029](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39895208029)
  (`7b799cf7`, all 18 jobs that ran green; the manual asset jobs skipped): `Nix (flake check)` and
  `macOS (Nix build + devenv)` run `nix flake check --print-build-logs`, which builds every check,
  so `ffmpeg-asset-pin-mismatch` built and passed on x86_64-linux and aarch64-darwin, the sha256
  case included. Item 8 ticked. Refactors approved by the user: the messages name the pinned asset
  through one `pinned` binding (`c05997a5`; every check and app derivation path unchanged), and
  the app is a `writeShellApplication` (`07a1e3ab`; `nix flake check --no-build --all-systems`
  green). Discovered and confirmed by the user: the ffmpeg artifact's integrity gates hold on
  darwin (`ffmpeg-artifact-integrity` is Linux-only today), added to the test list.

- 2026-10-23 (cycle 8, test-list item 9, must not break; no red by nature): the same run keeps
  `E2E (Playwright)`, the three Linux desktop e2e jobs, `Nix (flake check)`, `Nix (build)`,
  `macOS (Nix build + devenv)` and `macOS (DMG + packaged e2e)` green. Open: the Linux asset/pin
  — `nix/asset-pin-check.nix` changed in cycle 7 and its two refactors, and no CI job runs the
  Linux producer app, so the user ran `nix run .#ffmpeg-asset-pin` and
  `nix run .#whisper-cpp-asset-pin` on Linux at `65672fff`: both printed `…: ok`. Open: the
  discovered integrity item still changes the flake, so item 9 is ticked on the run after it.

- 2026-10-23 (cycle 9, discovered item: the ffmpeg artifact's integrity gates hold on darwin): the
  test is item 62's `ffmpeg-artifact-integrity` (`nix/artifact-integrity-check.nix`), now required
  on aarch64-darwin as well. Red (local `nix eval`): `checks.aarch64-darwin.ffmpeg-artifact-integrity`
  does not exist — the flake removed it on darwin — while x86_64-linux evaluates to
  `cm28y6i0…-ffmpeg-artifact-integrity.drv`. Green: the check takes this system's inputs (the
  fixture mirrors the pinned asset's archive type, `.tar.zst` on Linux and `.tar.gz` on darwin;
  the stale store path is a build path of this system's artifact name,
  `/nix/store/0000…-ffmpeg-audio-only-8.1.2` on darwin) and is no longer removed on darwin.
  Locally both systems evaluate — darwin to `gvsjhclai…-ffmpeg-artifact-integrity.drv`, Linux to
  the unchanged `cm28y6i0…` — so the evaluation-time gate already holds on darwin: a pin made from
  another build throws, and its error names the fixture file, its url, the sha256 and both store
  paths. `nix flake check --no-build --all-systems` passes. The build-time gates (the fetch of a
  wrong-sha256 and a missing asset, the stray-path scan, the real darwin prebuilt's own closure)
  need a real store, so the item stays unticked until a CI run of `macOS (Nix build + devenv)`
  builds the check.
  CI run [39942962117](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39942962117)
  (`55638a16`, `workflow_dispatch` with `assets: true`, all 20 jobs that ran green):
  `macOS (Nix build + devenv)`'s `nix flake check` builds every check, so the darwin
  `ffmpeg-artifact-integrity` built and passed — the wrong-sha256 and missing fetches, the
  stray-path scan and the real darwin prebuilt's closure included. The discovered item ticked.
  Refactor approved by the user: the helper derives the stale store path from the source build's
  name (`c438f905`); every caller's old literal had that form, all four integrity derivations
  (ffmpeg and whisper-cpp, both systems) keep their `.drv` paths, and forcing them re-ran the
  stale-pin gate with the derived path. The second suggestion (one per-system input set) no longer
  applies: only the fixture name still differs by system.

- 2026-10-23 (cycle 8 closed, test-list item 9): the same run, after the last flake change, keeps
  Linux e2e, the three Linux desktop e2e jobs, `Nix (flake check)`, `Nix (build)`,
  `macOS (Nix build + devenv)`, `macOS (e2e)` and `macOS (DMG + packaged e2e)` green, and the
  macOS asset job's `Prove the recorded pin matches the built asset` ran the rebuilt darwin
  `ffmpeg-asset-pin` app green; the Linux producer apps were green at `65672fff` (user's run) and
  `nix/asset-pin-check.nix` has not changed since. `c438f905` came after the run and changes no
  derivation. Item 9 ticked. All test-list items are ticked; status set to `in-review` by the
  workspace agent, which stops here (no `done`, no merge, no push).

- 2026-10-23 (root review, returned by the user): the root agent's review found seven defects, all
  sent back to the workspace; status back to `in-progress`.
  1. (MED) `macos-ffmpeg-asset` proves the recorded pin before it packages the asset, and the pin
     check throws at evaluation on a stale pin — so after an enable-list or nixpkgs change the job
     fails before it builds the tar or prints the new pin, the very rebuild it exists for. The job
     must always produce and print the asset and its pin; the pin proof still fails the job, after
     packaging, when the recorded pin does not match.
  2. (MED) Cycle 4's log says run 39886710824 built the fixtures in `macOS (e2e)` and in
     `macOS (DMG + packaged e2e)`; the DMG job's `playwright.desktop.config.ts` has no global
     setup and `prepareE2eFixtures` only downloads, so only `macOS (e2e)` ran the generator.
  3. (LOW-MED) Items 1 and 2 are ticked on pre-rebase commits (`800d08fd`, `b311e454`,
     `20e96b7f`, `535797d1`) with no CI run ids.
  4. (LOW) `nix/asset-pin-check.nix` takes the asset's file name from the pin's url, so the old
     evaluation gate (the pin's url names the file this build gives its asset) became
     tautological; a pin whose url names another file now passes evaluation and fails later as a
     missing asset. Cycle 7's "tautological, so gone" rationale is wrong.
  5. (LOW) Stale docs: README.md:134 (the macOS shell's ffmpeg is the pinned darwin artifact, not
     `ffmpeg_8-headless`), devenv.nix:26-29 (the darwin shell does not use the source build),
     README.md:138 (on Linux `./gradlew`'s managed ffmpeg is bytedeco's full build).
  6. (LOW) `e2e/lib/fixtures.test.ts`'s header says `./gradlew e2e` puts the slim build first on
     PATH — true on macOS only, so only macOS CI enforces the slim-surface invariant; and the test
     never asserts the WAV header's RIFF size, byte rate or block align.
  7. (LOW) The `assets` input's description names a Windows whisper-cli job this branch does not
     have (item 49's).

- 2026-10-23 (record corrections, root-review findings 2 and 3):
  - Finding 2: cycle 4 claimed that run 39886710824 also built the fixtures in
    `macOS (DMG + packaged e2e)`. It did not: that job runs `playwright.desktop.config.ts`, which
    has no global setup, `prepareE2eFixtures` only downloads the pinned files, and the packaged
    specs copy `TEST_JFK_WAV` themselves. `macOS (e2e)` is the only macOS job that runs the
    generator — and the only job that runs it with the slim darwin build; the Linux
    `E2E (Playwright)` job runs it with bytedeco's full build. The cycle-4 entry is corrected in
    place.
  - Finding 3: cycles 1–3 cite commits from before the rebase onto `main` (`800d08fd` and
    `b311e454` in cycle 1, `20e96b7f` in cycle 2, `535797d1` in cycle 3; `dc6ba4e8` is the
    harness-bug run cycle 1 mentions without a hash, and `2832b54c` is item 2's red). They are no
    longer on the branch; their content is in `6d822a1b`. Their public CI runs, found by head SHA:
    - Item 1 red: [39870450443](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39870450443)
      on `800d08fd` — `macOS ffmpeg asset (manual)` failed at "Build the audio-only ffmpeg and
      prove its surface" (the missing-compiler configure error quoted in cycle 1).
    - The harness bug cycle 1 mentions: [39871483754](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39871483754)
      on `dc6ba4e8` — the asset job built and proved the binary, then failed at "Package the
      asset, print the pin" (`find result` did not follow the output symlink).
    - Item 1 green: [39873554607](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39873554607)
      on `b311e454` — `macOS ffmpeg asset (manual)` green (the run's `macOS (e2e)` failed; it is
      not item 1's evidence).
    - Item 2 red: [39877149237](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39877149237)
      on `2832b54c`, not on `20e96b7f` as cycle 2 says — the asset job failed at "Prove the
      fixtures and the app's commands on darwin". The quoted `does not provide attribute …` error
      is that step's failure; the step text itself needs a GitHub login to read.
    - Item 2 green: [39878801497](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39878801497)
      on `20e96b7f` — `macOS ffmpeg asset (manual)` and `macOS (Nix build + devenv)` green; the
      run was cancelled afterwards, before its other macOS jobs finished.
    - Item 3 green: [39880624864](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39880624864)
      on `535797d1`, as cycle 3 already cites.

- 2026-10-23 (cycle 10, root-review finding 4): the asset-pin check takes the asset's file name
  from the pin's url (`baseNameOf pin.url`) since cycle 7, which made its old "the pin's url does
  not name <file>" gate tautological — or rather, removed the gate's subject: any url on the tag
  passed evaluation, and a url naming another file failed later as "the asset … is missing".
  Test: the new `ffmpeg-asset-pin-mismatch` case "another file's url" — a pin whose url names the
  other compression's file (`…tar.zst` on darwin, `…tar.gz` on Linux) must fail at evaluation,
  naming the expected file and the url; the fixture's expected name is spelled out independently
  (the fixture source's name, the system, the compression of `nix/release-asset.nix`). Red (local
  `nix eval`, both systems): `ffmpeg-asset-pin-mismatch: another file's url was accepted`. Green:
  `nix/release-asset.nix` exposes its asset's name as `passthru.file`, `nix/asset-pin-check.nix`
  takes `file = asset.file` and rejects a pin whose url does not name it —
  `ffmpeg-asset-pin: the pin's url …/ffmpeg-asset-pin-fixture-source-aarch64-darwin.tar.zst does
  not name the ffmpeg-asset-pin-fixture-source-aarch64-darwin.tar.gz this build makes` (and the
  Linux counterpart). The real pins still pass (the real asset's name is its url's base name on
  both systems), the two `ffmpeg-asset-pin` apps and `whisper-cpp-asset-pin` keep their derivation
  paths, and `nix flake check --no-build --all-systems` is green. The item ticked; cycle 7's
  "tautological, so gone" rationale is corrected in place.

- 2026-10-23 (cycle 11, root-review finding 6, the WAV header): `e2e/lib/fixtures.test.ts` now
  reads the fmt chunk's byte rate and block align and the RIFF size, and every generated clip must
  satisfy the format's own rules — RIFF size = file size − 8, byte rate = sample rate × channels ×
  bytes per sample, block align = channels × bytes per sample (`<file>'s WAV header is
  inconsistent`). Red against two temporary mutations of `wavPcm16` (restored each time):
  `SAMPLE_RATE * 2` → `* 4` for the byte rate, and `36 + data.length` → `32 + data.length` for the
  RIFF size; each failed with `AssertionError: jfk-a.wav's WAV header is inconsistent` and the
  field's diff. Green with the real writer: the fixture test and `processes.test.mjs` pass,
  `tsc -p desktop/tsconfig.json` and `prettier --check .` are clean. Item ticked.

- 2026-10-23 (cycle 12, root-review finding 1; CI-only macOS): the job proved the recorded pin
  before packaging, and every pin-dependent step throws at evaluation on a stale pin — so after
  the enable-list or nixpkgs change the job exists for, it failed before building or printing the
  new asset. Red at `f8e587b7` (the darwin pin's storePath replaced with the all-zeros
  placeholder `/nix/store/0000…-ffmpeg-audio-only-8.1.2`, nothing else) in CI run
  [39959694838](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39959694838):
  `macOS ffmpeg asset (manual)` failed at "Prove the fixtures and the app's commands on darwin"
  (step 5 — those checks run the pinned prebuilt), and "Prove the recorded pin", "Package the
  asset, print the pin" and the upload were all skipped. Green at `f4d996b4`, with the stale pin
  still in place, in run
  [39962008337](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39962008337):
  "Package the asset, print the pin" ✓ (the asset and its pin are printed and in the job summary),
  "Prove the fixtures and the app's commands on this build" ✓ (the new `ffmpeg-source-surface`
  app: the same two scripts as the `ffmpeg-upload-formats` and `ffmpeg-app-uses` checks, on
  `nix/ffmpeg.nix`'s source build rather than the pinned prebuilt), "Prove the recorded pin
  matches the built asset" ✗ — the job still fails on the stale pin, after packaging — and the
  upload ✓ (`if: always()`), so the tar to publish survives the failure. Item ticked. The real pin
  is restored in the next commit; that dispatch is the item's final green.

- 2026-10-23 (cycle 12 closed, root-review findings 1–7; final green): CI run
  [39967927776](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39967927776)
  (`648df4bb`, `workflow_dispatch` with `assets: true`) is green in every job that ran;
  `Pinned model hosts resolve` skipped as usual. The asset job ran the new order with the real
  pin restored (`19b6beda`): "Build the audio-only ffmpeg and prove its surface", "Package the
  asset, print the pin", "Prove the fixtures and the app's commands on this build" and "Prove
  the recorded pin matches the built asset" all passed, and the upload ran. `macOS (DMG +
  packaged e2e)` passed "Assert the packaged ffmpeg is the audio-only build" and the four
  packaged specs; `macOS (e2e)` ran `gradle e2e` and the Metal assertion; `macOS (Nix build +
  devenv)` passed actionlint, `devenv test`, the shell's pinned-ffmpeg assertion,
  `nix flake check` and `nix build`; the Linux jobs stayed green. The seven findings are
  answered: finding 1 by `f4d996b4`/`19b6beda` (this run), findings 2 and 3's run ids by
  `cb0d9273`, finding 4 by `08d34fe2`, finding 5 by `f8186cb1`, finding 6 by `56cdb826` (the
  WAV header assertions) and `45761c50` (the header text), finding 7 by `16d36791`. The approved
  refactor `648df4bb` names the app-surface script invocations once in `flake.nix`; all four
  check derivations and both `ffmpeg-source-surface` app scripts keep their derivation paths on
  both systems, and `nix flake check --no-build --all-systems` exits 0. Status set to
  `in-review`; the workspace agent stops here (no `done`, no merge, no push).
- 2026-10-23 (root review round 2, PR prep, the user): the four remaining findings are fixed in the
  PR commit. (1) `nix/asset-pin-check.nix` compares `builtins.baseNameOf pin.url` with the build's
  asset file instead of `hasInfix "/${file}"`, and `ffmpeg-asset-pin-mismatch` gained the sidecar
  case (`…tar.gz.sha256`), which the old gate accepted. (2) The "only job that runs the generator"
  sentence is scoped: the Linux `E2E (Playwright)` job runs it with bytedeco's full build; only the
  macOS slim-surface run is `macOS (e2e)`. (3) Cycle 12's red is described as the all-zeros
  placeholder storePath it actually set. (4) The pre-rebase hashes are listed under the cycles
  that cite them. `nix` is unavailable in the root sandbox, so the flake check that builds
  `ffmpeg-asset-pin-mismatch` is the user's run.
