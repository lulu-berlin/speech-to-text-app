# 62 — An audio-only ffmpeg, shipped as a pinned prebuilt artifact

Status: done (roadmap item 62, approved 2026-10-19)
Depends on: [56](desktop-bundle-mesa-artifact.md) (done; the pattern this item
generalizes), [61](headless-ffmpeg.md) (done; its headless baseline and its committed
format fixtures are reused here)
Blocks: —

## Goal

The flake ships an ffmpeg built for exactly what the backend does — decode the audio track of any
accepted upload (the common audio families; see Decided), `silencedetect`/`atrim`, the canonical FLAC encode and the V10 `concat` backfill —
as a static single binary with no video codecs, subtitle rendering, network protocols, capture devices
or hardware acceleration. Being a custom build it is not in cache.nixos.org, so on Linux x86_64 it
comes from a versioned, sha256-pinned GitHub Release asset, the way item 56 brings the bundle's
Vulkan-only Mesa; a system without an asset builds it from source.

## Evidence

- Item 61's measurement (2026-10-18, cache.nixos.org narinfo, uncompressed NAR): the headless
  `bin` closure is 305.1 MiB, 115 paths. Its `lib` output's direct references include the video and
  extra-feature libraries x265 23.8, libvpx 8.6, libaom 8.4, svt-av1 7.5, dav1d 2.5, x264 2.4,
  xvidcore, libtheora, openjpeg, libwebp, zimg, libass (+ fontconfig, freetype, harfbuzz, fribidi),
  libbluray, srt, librist, libssh, gnutls, v4l-utils, libva-minimal, vulkan-loader and ocl-icd.
- Keeping only the references an audio path can use (glibc, bzip2, xz, libopus, libvorbis, soxr,
  speex, lame, ffmpeg's data output) leaves a 16-path, 51.7 MiB closure, of which glibc is 33.9 MiB —
  plus ffmpeg's own outputs (35.2 MiB today, smaller without the video codecs).
- The app's ffmpeg surface (read 2026-10-18): `AudioDecoder` decodes to `f32le` (`-ac 1 -ar 16000`);
  `AudioChunker` runs `silencedetect` and encodes the canonical FLAC (`-c:a flac -frame_size 4000`);
  `WindowCutter` runs `atrim=start_sample:end_sample` and encodes FLAC; `V10__BackfillCanonicalClip`
  reads a `concat` list and runs the canonical encode. No other ffmpeg feature is used.
- ffmpeg decodes Opus, Vorbis, MP3, AAC, FLAC, WMA and AMR natively; no external encoder is needed
  (LAME only encodes MP3, which the backend never does). `[INFERENCE]` on a static build the external
  audio libraries can be dropped too, leaving glibc plus ffmpeg's own code.
- The upload accepts 19 extensions (`backend/src/main/resources/allowed-extensions.json`): `mp3 wav
  flac ogg oga opus m4a aac wma aif aiff amr webm mp4 m4v mov mkv mka 3gp`. The video containers
  need their demuxers kept even though no video is decoded.
- `ffmpeg_8` is multi-output (`bin`, `lib`, `dev`, `doc`, `man`, `data`, `out`;
  nixpkgs `494ce7fd` `pkgs/development/libraries/ffmpeg/generic.nix:1039-1050`), and `bin`
  references `lib` and `data`: item 56's re-link handles one output path, this needs three — or, as
  decided, a static single-output build, where only the binary's own embedded configure line names
  the build path.

## Decided (consultation, 2026-10-18)

- **An explicit audio-only enable list** (the user): ffmpeg `--disable-everything` plus exactly the
  demuxers for the 19 extensions' containers and `concat`, the audio decoders those carry, the `flac`
  encoder, the `flac`/`f32le`/`null` muxers, the `silencedetect`/`atrim`/`aresample` filters and the
  `file` protocol. Nothing else. The format check is the safety net: a missing demuxer or decoder
  fails there, not in production.
- **Rework 2026-10-19** (the user, after the root agent's review): the enable list gains the common
  audio families an accepted upload can carry — the container-relevant PCM family (all bit depths,
  endiannesses and float, `pcm_s24le`/`pcm_s32be`/`pcm_f32be` included), AAC LATM (`aac_latm` +
  `loas`), Speex, WMA v1/v2/Pro/Lossless/Voice, DTS, TrueHD/MLP, ATRAC, MP1, ADPCM (WAV/MS/QT) and
  G.722 — 58 decoders in total. Encoders stay FLAC/PCM; external-library (`lib*`) codecs, video and
  the legacy/media-specific PCM decoders (`pcm_bluray`, `pcm_dvd`, `pcm_s24daud`, `pcm_lxf`,
  `pcm_sga`, `pcm_vidc`, `pcm_f16le`, `pcm_f24le`) remain off. The variant fixtures prove the class,
  and the exact-set check pins the list.
- **A static single binary** (the user): `--disable-shared --enable-static` in a static package set
  (`pkgsStatic`), so the output is one executable with no shared libraries, no RUNPATH and one store
  path to pin and re-link. **No ffprobe** (the user: "we don't need ffprobe"): the app links only
  `ffmpeg`, and static linking would duplicate its code into a second binary.
- **A shared artifact helper** (the user): item 56's fetch/re-link/scan/pin-gate code moves into
  `nix/prebuilt-artifact.nix` and both artifacts use it; item 63 (whisper-cpp) is expected to reuse
  it too. The Mesa's four flake checks stay green over the refactor.
- **Scope** (the user): the desktop (`.#desktop`, and so `.#desktop-bundle`), the server package
  (`nix build .`, and so `.#docker-root`) and the devenv shell. The shell loses `ffprobe` (and
  already lost `ffplay` in item 61); that is documented where the shell's tools are described.
- **Committed tiny fixtures** (the user): the per-extension fixtures and the format check are item
  61's and are reused here, not duplicated. `amr`/`wma` fixtures are generated once with another
  ffmpeg because the slim build has no such encoder.
- **Its own release tag** (the default, as for Mesa): `desktop-ffmpeg-v1`; a rebuild with another
  enable list or on a nixpkgs bump gets a new tag (`desktop-ffmpeg-v2` after the 2026-10-19 rework),
  a prerelease asset built by a flake package that prints its pin; the user builds and uploads, agents
  never publish. The stale-pin gate fails evaluation until the pin is updated.

## Proposal

1. Build the static audio-only ffmpeg with the enable list above, in a derivation whose name matches
   the final derivation's name so the asset's embedded build path can be rewritten in place
   (same-length paths; item 56's constraint).
2. Extract `nix/prebuilt-artifact.nix` from `nix/desktop-mesa.nix`: the fixed-output fetch that fails
   naming the asset, the unpack-and-re-link step, the stray-path scan, the `builtins.appendContext`
   pinning of references and the evaluation-time stale-pin gate. Wire Mesa through it without changing
   its behavior, then add `ffmpeg.<system>` to `nix/desktop-artifacts.nix`.
3. Use the artifact in `nix/desktop.nix`, the server wrapper in `flake.nix` and `devenv.nix`.
4. Flake checks: the asset builder printing the pin; missing/hash-mismatch asset; stale pin; stray
   store path; every pinned reference in the closure; and item 61's per-extension decode check, run
   against this binary.

## Open questions

- The asset's exact name versus the final derivation name (same store-path length) — measured and
  fixed in the first cycle.
- Which store paths a static binary names at all (its configure line, any data file); the scan and the
  pinned `references` list are read off the first build.
- Does anything in the devenv (developer scripts, e2e helpers) call a demuxer outside the 19 formats?
  The app's own surface is covered; ad-hoc shell use may not be, and the slim build is a documented
  tradeoff.

## Test list (approved 2026-10-18; behaviors only)

- [x] On x86_64-linux, the desktop (`.#desktop`, and so `.#desktop-bundle`), the server package
      (`nix build .`, and so `.#docker-root`) and the devenv shell get the same audio-only ffmpeg:
      the pinned release asset, not a source build (a dry-run on a store without it lists no ffmpeg
      derivation to build)
- [x] The shipped binary is static (no dynamic dependencies), ships no ffprobe, and its
      `ffmpeg -version` configure line shows the audio-only build: the demuxers, audio decoders,
      `flac` encoder, `flac`/`f32le`/`null` muxers, `silencedetect`/`atrim`/`aresample` filters and
      the `file` protocol — and no video codecs, hwaccels, capture devices or network protocols
- [x] Every upload format the app accepts (the 19 extensions in `allowed-extensions.json`, using item
      61's committed fixtures) decodes to the canonical FLAC with the shipped binary
- [x] The app's other ffmpeg uses still work with it: `f32le` PCM decode, `silencedetect`, the `atrim`
      window cut and the V10 `concat` backfill
- [x] A registered asset that is missing or does not match its sha256 fails naming the asset, url and
      sha256; an asset made from another source build fails at evaluation naming both store paths;
      the stray-path scan fails on an unregistered path; every pinned reference is in the output's
      closure; no occurrence of the asset's build store path survives
- [x] The ffmpeg closure in the bundle, the server package, `docker-root` and the devenv contains none
      of the video libraries the slim build drops (x265, libvpx, libaom, svt-av1, dav1d, x264); their
      sizes are measured and recorded. The bundle's closure is at or below item 61's measured size;
      any growth stops the item for the user's decision
- [x] The shared artifact helper replaces item 56's inline Mesa code and serves both artifacts; the
      Mesa's four flake checks stay green on the refactored code, and the flake's asset builder prints
      the ffmpeg pin (the user builds and uploads the asset; agents never publish)
- [x] (must not break) Item 61's baseline: headless ffmpeg no longer ships anywhere; the packaged
      desktop specs (bundle 11+3, AMD 12+2, NVIDIA 12+2), `nix build .`, `nix flake check`, the
      Docker image and the e2e journey stay green; the devenv health check passes and the shell's
      `ffmpeg -version` is the slim build
- [x] (discovered from item 61's review, confirmed 2026-10-18) The upload-format proof is a flake
      check over the pinned artifact: each committed fixture is decoded by the shipped `ffmpeg` to
      the canonical FLAC, which is validated from its FLAC STREAMINFO (16 kHz, mono, s16, and the
      expected sample count) — no `ffprobe` anywhere, and a missing demuxer or decoder fails the
      check
- [x] (discovered from item 61's review, confirmed 2026-10-18) The upload-format check takes its
      extension list from `backend/src/main/resources/allowed-extensions.json`: an accepted extension
      without a fixture, and a fixture whose extension is no longer accepted, both fail it
- [x] (discovered, confirmed 2026-10-18) No test or doc assumes ffprobe: FLAC properties and the
      first-frame offset are read in Kotlin from the file itself (STREAMINFO, and the end of the
      metadata blocks for the packet position), `TestFixtures.streamInfo` and
      `TestAudioProbes.ffprobe` stop shelling out, and the README and devenv comments say the shell
      has neither `ffplay` nor `ffprobe`
- [x] (discovered, confirmed 2026-10-18) The Integration suite runs on the shipped binary alone:
      fixture generation and sample comparison use only the app's ffmpeg surface — no
      `lavfi`/`sine`/`anullsrc`/`aevalsrc`/`anoisesrc`, no `volume`, no `pcm_s16le`/`s16le`; PCM and
      WAV are synthesized in Kotlin, FLAC is encoded by the shipped `flac` encoder, and decodes use
      the enabled `f32le` muxer

- [x] (discovered from the root agent's review, confirmed 2026-10-19) Accepted-upload variants the
      19 fixtures do not cover decode to the canonical FLAC through the shipped binary and validate
      from STREAMINFO: a 24-bit WAV (`pcm_s24le`), a 32-bit big-endian MKA (`pcm_s32be`), a 32-bit
      float big-endian AIFF (`pcm_f32be`) and a LATM `.aac` (`aac_latm` through `loas`); item 61's
      fixtures stay untouched and a missing decoder fails the check
- [x] (discovered from the root agent's review, confirmed 2026-10-19) The audio-only check compares
      the configure line's exact component sets for demuxer, decoder, parser, encoder, muxer, filter
      and protocol, asserts the set of enabled kinds, and names every extra or missing component, so
      an added video decoder cannot pass

e2e-exempt (ADR 0046): a packaging item that changes no app feature; the packaged specs and journey
are its end-to-end proof.

## Acceptance criteria

- Every shipped ffmpeg is the same static audio-only binary from the pinned asset; no source build is
  in any of the four build graphs; every accepted format decodes and the app's four other operations
  work; the closures contain none of the dropped video libraries and do not grow.
- One shared artifact helper serves Mesa and ffmpeg, and the Mesa's checks stay green over the
  refactor.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request, split from item 61 at its
  consultation (the user: item 61 switches to the cached headless ffmpeg; this item produces a
  really slimmed-down audio-only build, which needs a build artifact). Not yet discussed.
- 2026-10-18: consultation — decisions: an explicit audio-only `--disable-everything` enable list;
  a static single binary with no ffprobe; item 56's artifact code extracted into a shared
  `nix/prebuilt-artifact.nix` (item 63 is expected to reuse it); desktop, server package, Docker and
  the devenv all ship it; item 61's committed fixtures and format check are reused; its own
  `desktop-ffmpeg-v1` release tag. Test list approved as worded above. Status: spec-approved. Filed
  on `main` through a docs PR; implementation starts after item 61 lands.

- 2026-10-18 (consultation, confirmed by the user): the four test-list additions above — the format
  proof runs the shipped binary and validates the FLAC STREAMINFO without ffprobe; the extension
  list is read from `allowed-extensions.json`; no test or doc assumes ffprobe; the Integration
  helpers use only the app's ffmpeg surface (the test-only lavfi sources, `volume`, `pcm_s16le` and
  `s16le` are replaced, not added to the enable list). First cycle: test-list item 2 (static
  binary, no ffprobe, audio-only configure line), because it produces the binary the other items
  ship and settles the name. Asset, source build and re-link output are all named
  `ffmpeg-audio-only-8.1.2` (same length; version from the pinned nixpkgs, so `versions.json` keeps
  ffmpeg 8.1).

- 2026-10-18 (cycle 1, test-list item 2, the user's runs): red at `420963b1` —
  `nix build --no-link -L .#checks.x86_64-linux.ffmpeg-audio-only` failed at evaluation,
  `Path 'nix/ffmpeg.nix' does not exist in Git repository`. First attempt at `5338660a` built the
  static binary (37 s; `patchelf: cannot find section '.dynamic'` on `bin/ffmpeg`; `install-progs`
  installed only `ffmpeg`), but the check failed because FFmpeg sh-quotes configure values with
  commas (`--enable-demuxer='aac,aiff,...'`). Green at `961a0db1` (the user's run): the check strips
  the quotes and passes. The pinned FFmpeg source settled four build necessities before the first
  build: the `f32le` muxer's configure component is `pcm_f32le`, the null muxer's codec is
  `pcm_s16le`, `-f null -` writes to `pipe:`, and `-ac`/`-ar`/`-sample_fmt` auto-insert `aformat`
  (with `aresample`); the check's expected list was corrected accordingly, not weakened.
  `pkgsStatic` is a musl cross set, so the generic recipe's cross flags
  (`--enable-cross-compile`, `--cross-prefix`, `--host-cc`) were restored; the host compiler's
  store path is stripped from `config.h` while the `--prefix=$out` build path stays for the
  re-link. Measured name: `source.name` is
  `ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2` (the static marker and musl host
  suffix are part of it; the consultation's shorter `ffmpeg-audio-only-8.1.2` was wrong), and the
  re-link output and asset use it. The check runs on `source`, the exact build the asset is made
  from; item 1 proves these bytes ship.

- 2026-10-18 (cycle 2, test-list item 7's asset builder, the user's runs): red at `5423cdc1`
  (`attribute 'desktop-ffmpeg-asset' missing`); green at `19975f03`: the check passed
  (`/nix/store/va9vdsdz…-ffmpeg-asset-pin`), and the builder printed the pin for
  `ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2-x86_64-linux.tar.zst` (4.11 MiB tar,
  1.70 MiB zstd): sha256 `65c502a0…3eb8`, storePath
  `/nix/store/h1a4z9w75gimkfwfvz6g4a3j9cy65wn1-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`,
  references `[]` (the binary's only store path is its own `--prefix`, which the re-link
  rewrites). The user uploads the tar to the `desktop-ffmpeg-v1` prerelease. Item 7 stays open
  for the shared helper extraction.

- 2026-10-18 (asset published, the user's run): `gh release view desktop-ffmpeg-v1` reports
  `isPrerelease: true` and the asset
  `ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2-x86_64-linux.tar.zst`,
  size 1,779,873 bytes, digest `sha256:65c502a02a07000decb05cba3bbe10cd26a4f2429e128facad39d0b9ae3b3eb8` —
  exactly the pin.

- 2026-10-18 (item 7's helper extraction, approved refactor, the user's runs at `49f571f9`):
  `nix flake check --print-build-logs` passed in 7 s — the four Mesa checks, both ffmpeg checks,
  `versions-sync`, `devenv-pin-sync` and `desktop-model-free`. The `directInputs` eval dump over
  the prebuilt Mesa's `drvAttrs` lists the asset's `.drv`, the build tools' `.drv`s,
  `store-path-scan.sh` and all 13 pinned references, so the helper's source-`.drv`-absent
  assertions see real inputs (not the known-issues vacuous case); `seesSourceDrv` is false.
  `nix/prebuilt-artifact.nix` takes `source`, `artifact`, `label`, `subject` and `extraAttrs`;
  `nix/desktop-mesa.nix` keeps exporting `source`, `pinError` and `mesa` (with `mesa.asset`).
  Item 7's "serves both artifacts" is completed by item 1's ffmpeg consumer.

- 2026-10-18 (cycle 3, test-list item 1, the user's runs; hashes after the rebase onto `main`
  `31ee8603`): red at `e7bb9973` ("nix/desktop-artifacts.nix has no ffmpeg.x86_64-linux entry").
  Green at `0dea91f9` plus three fixes: `9f4635fc` ignores `remove-references-to`'s
  `/nix/store/eeee…` placeholder in the stray scan; `b37f1165` restricts the scan's match to the
  store-name charset (the old `[^/]*` crossed spaces in ffmpeg's `configuration:` line, so the
  unquoted loop fed the flags to `grep` as options and echoed them as strays); `2ec3407a` drops the
  check's `source.drvPath` assertion, which pulled the source build into the check although the
  shipped path never uses it (the empty-store dry-run remains the no-source proof). The user's
  checks: the everywhere check green; `nix flake check` green; the empty-store dry-run `ok: no
  source ffmpeg` (only the asset fetch and the re-linked output; no source `.drv`); the devenv
  shell's `ffmpeg` resolves to
  `/nix/store/zxp9…-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2/bin/ffmpeg` (the
  `ffprobe` found there was the user's `~/.nix-profile`, not a shell package).
- 2026-10-18 (discovered during item 1; confirmed by the user): `.#default`, `.#docker-root` and
  `.#desktop-bundle` also held nixpkgs' `ffmpeg-headless-9.0.1` through `whisper-cpp`'s
  `withFFmpegSupport` (it adds the build input and wraps the unused `whisper-server`). The user
  approved `withFFmpegSupport = false` (`f1ff5bff`); the new whisper-cpp built, all three closures
  now hold only `…-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`, and `whisper-cli`
  transcribed the committed FLAC fixture and the JFK WAV (the smoke's JSON errors were `-oj`
  misuse, not audio). Item 1 ticked.
- 2026-10-18 (item 7, completed by item 1): the helper (`49f571f9` after the rebase) replaced
  Mesa's inline code and the Mesa checks stayed green (see the item-7 entry above);
  `nix/ffmpeg.nix` now consumes it (`0dea91f9`), so it serves both artifacts; the asset builder
  printed and the check verified the `desktop-ffmpeg-v1` pin. Item 7 ticked.
- 2026-10-18: `docs/known-issues.md`'s `directInputs` row is removed: the eval dump at
  `49f571f9` showed `builtins.getContext` over the prebuilt Mesa's `drvAttrs` lists the asset
  `.drv`, the build tools and all 13 pinned references, and not the source `.drv`; the row's only
  cited check (`ffmpeg-headless`) is retired by item 62.

- 2026-10-18 (cycle 4, test-list item 3, the user's runs): the new check `ffmpeg-upload-formats`
  derives the 19 extensions from `allowed-extensions.json`, decodes each committed fixture with the
  shipped artifact, and validates the result's own FLAC STREAMINFO (16 kHz, mono, s16, 0.4-0.6 s)
  without ffprobe; a fixture for an unaccepted extension fails too. The first run failed on
  `sample.webm`: the FLAC output mapped the VP9 video track and asked for a png encoder the build
  does not have. Discovery (confirmed by the user): the app's audio-only commands never ignored
  video; `-vn` was added to the canonical args (encode and V10), `AudioDecoder`, `WindowCutter` and
  `silencedetect` (`31e0ad39`), and to the check; `gradle check` green; the user's rerun green
  (`/nix/store/6595jj07…-ffmpeg-upload-formats`). Item 3 ticked.

- 2026-10-18 (cycle 5, test-list item 4, the user's run): the new `ffmpeg-app-uses` check mirrors the
  app's four remaining command lines against the artifact — f32le decode of the video webm,
  `silencedetect` to the null muxer/`pipe`, the `atrim` window (STREAMINFO total 3200) and the
  two-clip V10 `concat` backfill (STREAMINFO total 16000). Green
  (`/nix/store/nrzx…-ffmpeg-app-uses`); its log shows only the audio stream mapped and
  `pcm_s16le` written to the null muxer. Item 4 ticked.

- 2026-10-18 (cycle 6, test-list item 5, the user's run): the new `ffmpeg-artifact-integrity` check
  runs the artifact's own fetch step against a `file://` fixture with a wrong sha256 and a missing
  URL (both fail naming the fixture asset, URL and sha256), asserts the stale-pin fixture throws
  while naming both store paths, feeds the scanner an unregistered `/nix/store/000…-rogue` path
  (fails and prints it) and a declared reference (passes), and checks the real prebuilt: no old
  build path, no stray path, and the artifact in its own runtime closure. Green
  (`/nix/store/6ny9…-ffmpeg-artifact-integrity`). Item 5 ticked.

- 2026-10-18 (cycle 7, test-list item 6, the user's runs): `ffmpeg-closure-slim` green. Sizes:
  artifact 4,300,144 B; `.#default` 1,770,516,960; `.#docker-root` 2,804,701,982;
  `.#desktop-bundle` 1,563,785,607 (the check's `du`) / 1,567,134,496 (`nix path-info -S`) — all
  below item 61 (default 1.8 GiB, docker 2.8 GiB, bundle 1,855,013,480 B). The only ffmpeg in the
  three closures is the artifact; no video library. The earlier +92 MiB growth was
  `nix/nvidia-driver-libs.nix` listing full packages: the payload's closure realizes the default
  outputs, so `pkgs.stdenv.cc.cc` packed the full gcc (isl/libmpc/linux-headers/glibc-dev, ~400 MB)
  for libstdc++. The user approved `lib.getLib` for every entry (`f79ebeda`); the gcc group grep is
  empty and the bundle dropped ~288 MiB below the baseline. Item 6 ticked.
- 2026-10-18 (review additions 1 and 2, completed by test-list item 3): `ffmpeg-upload-formats`
  (`783889d3`, `-vn` in `31e0ad39`) runs the shipped artifact over every fixture, validates the
  result from its STREAMINFO without ffprobe, reads the extension list from
  `allowed-extensions.json`, and fails on drift in both directions. Additions 1 and 2 ticked.

- 2026-10-18 (addition 3, first cycle): red `NotImplementedError` at `FlacStreamInfoSpec.kt:19`;
  green at `02e84f07`: the new `flacStreamInfo` reads `sample.flac`'s STREAMINFO and first frame
  from its bytes (44.1 kHz, mono, s16, 22 050 samples; the first frame's sync at the metadata end)
  and the unit spec passes; `gradle check` green. `streamInfo` and the HTTP probes still call
  ffprobe until the next steps.

- 2026-10-18 (addition 3, completed): `TestFixtures.streamInfo` now reads the FLAC STREAMINFO
  (`4ce129a9`); the HTTP probes take the sample total and the packet offset from the header
  (`18b6110e`), deleting `TestAudioProbes.ffprobe`; the root Gradle bootstrap stops resolving
  ffprobe for the integration tests (`89261458`). `gradle check` and the three HTTP Integration
  specs green; the user's `UploadFormatsSpec` on the artifact green (the chunker plus the reader).
  Addition 3 ticked.

- 2026-10-18 (addition 4, first cycle): `AudioDecoderSpec` now builds its fixtures in Kotlin —
  `writeWav`/`sineWav`/`emptyWav` (`e843f5b0`), no lavfi. The first run's peak was 0.1767 because
  the stereo downmix scales identical channels by √2; the generator now scales each channel by
  1/√channels, matching lavfi's mono→stereo upmix, and the peak is 0.125 again. `gradle check` and
  `AudioDecoderSpec` are green in the sandbox; the user's artifact run green.

- 2026-10-18 (addition 4, completed): the Integration suite runs on the shipped binary. Fixtures are
  Kotlin (`writeWav`/`toneWav`/`sineWav`/`generateNoise`), FLACs use the shipped `flac` encoder,
  sample comparisons decode `f32le` and convert in Kotlin (`toS16`/`decodeSamples`), and the
  diarization recording is a Kotlin WAV; no `lavfi`/`s16le`/`pcm_s16le`/`volume`/`anoisesrc`
  remains (only a captured `lavfi` log line and a scratch file name mention them). Cycles: decoder
  `e843f5b0`, noise/FLAC/PCM `d5a817ee`, canonical serving `ce31a097`, long playback `082b56f1`,
  chunker `b503ef71`, storage `51e0e4ac`, diarization `e30e4ce9`. The user's full
  `gradle integrationTest` on the artifact is green (1m15s). Addition 4 ticked.

- 2026-10-19 (item 8, the user's runs on the rebased tree `5550f21c`, main `d3ebbe19`): the frontend
  was rebuilt; `nix flake check` green (backend check, the ffmpeg checks, the Mesa checks; artifact
  4,300,144 B; `ffmpeg-closure-slim`: default 1,770,543,370, desktop-bundle 1,563,738,141,
  docker-root 2,804,728,392 — the bundle below item 61's 1,855,013,480); `nix build .` green; devenv
  `ffmpeg version 8.1.2`; the e2e journey **40 passed** (7.8m, including item 59's specs and item
  60's device probe) with a Nix `ffmpeg_8-full` on PATH for the fixture generator — the shell's slim
  ffmpeg intentionally lacks its test-only `wav`/`pcm_s16le`/`concat`, the Gradle-managed bytedeco
  is a generic binary NixOS cannot run, and CI's ubuntu `gradle e2e` remains the standard path (the
  README now states the full-ffmpeg requirement); packaged specs: `.deb` 11+3, nix desktop 11+3,
  bundle/Alpine 11+3, bundle AMD 12+2, bundle NVIDIA 12+2. The Docker image and the CI jobs run on
  the PR; the `docker-root`/image graph is green locally. Item 8 ticked.
- 2026-10-19 (rework from the root agent's review; red for the variant item, the user's run on
  `e7cd6164`): `ffmpeg-upload-formats` fails at the first variant the 19 fixtures miss —
  `[aist#0:0/pcm_s24le …] Decoding requested, but no decoder found for: pcm_s24le` and
  `the shipped ffmpeg failed to decode variant-24bit.wav`; the 19 `sample.*` fixtures pass.
- 2026-10-19 (rework, finding 2): the audio-only check now parses every `--enable-<kind>=` value and
  compares the exact component sets, single-sourced from `nix/ffmpeg.nix`; `nix/ffmpeg-audio-only.sh`
  is locally runnable. Local before/after: the old containment accepts a configuration with `h264`
  appended; the new check fails `enables decoder h264, …`; a missing `pcm_s16le` fails; an added
  `--enable-hwaccel=cuvid` fails. `nix/ffmpeg.nix`'s flags are unchanged (same out path), and
  `nix/desktop-artifacts.nix`'s header now names both readers.

- 2026-10-19 (rework, scope and asset): the user's set is the common audio families, not all 220
  native decoders: the whole container-relevant PCM family, AAC LATM (`aac_latm` + `loas`), Speex,
  WMA Pro/Lossless/Voice (v1/v2 were already in), DTS, TrueHD/MLP, ATRAC, MP1, ADPCM (WAV/MS/QT)
  and G.722 — 58 decoders. Encoders stay FLAC/PCM. The enable-list change moves the source out path,
  so the asset is rebuilt and published as `desktop-ffmpeg-v2`; its pin is the user's paste.

- 2026-10-19 (rework, the user's asset build): `.#desktop-ffmpeg-asset` printed the v2 pin — sha256
  `945db2902395cfc3a5c002de35fbddc4b3ea14de76ee50d5b3bbfbce98fc184c`, storePath
  `/nix/store/2pqr6g1ll2dzj7p87cz5a6w8amv2pp22-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`,
  references `[]` — and it is recorded in `nix/desktop-artifacts.nix`. The user uploads the tar to
  the `desktop-ffmpeg-v2` prerelease.

- 2026-10-19 (rework, green — the user's `nix flake check --print-build-logs` on `69f42228`): the
  uploaded v2 tar is fetched and the pin matches (sha256 `945db290…`, storePath `2pqr…`); the check
  exits clean, with `ffmpeg-asset-pin`, the exact-set `ffmpeg-audio-only` expected file, the app-use
  check and the backend `check` in the log. `ffmpeg-closure-slim`: artifact 4,916,840 B, default
  1,771,160,066, desktop-bundle 1,564,784,978 (below item 61's 1,855,013,480), docker-root
  2,805,345,088. Both review test-list items ticked. The packaged specs and the e2e journey were
  green on the v1 asset; the PR CI's jobs rerun them on v2.

- 2026-10-19 (landing): the rework was re-reviewed by the root agent — findings 1-3 fixed (the
  decoder surface + v2 repin, the exact-set audio-only check, the stale comment), finding 4's
  closures re-measured and recorded — and the user approved landing. The sandbox's `gradle check`
  and `gradle integrationTest` are green on `06f0df4d`; the user's `nix flake check` is green on
  the same tree. Squashed onto `main` (`d3ebbe19`) as the item's single commit; the PR CI reruns
  the packaged specs and the 40-spec e2e journey on the v2 asset.
