# 62 — An audio-only ffmpeg, shipped as a pinned prebuilt artifact

Status: spec-approved
Depends on: [56](../docs/specs/desktop-bundle-mesa-artifact.md) (done; the pattern this item
generalizes), [61](../docs/specs/headless-ffmpeg.md) (done; its headless baseline and its committed
format fixtures are reused here)
Blocks: —

## Goal

The flake ships an ffmpeg built for exactly what the backend does — decode the audio track of any
accepted upload, `silencedetect`/`atrim`, the canonical FLAC encode and the V10 `concat` backfill —
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
- **Its own release tag** (the default, as for Mesa): `desktop-ffmpeg-v1`, a prerelease asset built by
  a flake package that prints its pin; the user builds and uploads, agents never publish. A rebuild
  on a nixpkgs bump gets `v2`, and the stale-pin gate fails evaluation until the pin is updated.

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

- [ ] On x86_64-linux, the desktop (`.#desktop`, and so `.#desktop-bundle`), the server package
      (`nix build .`, and so `.#docker-root`) and the devenv shell get the same audio-only ffmpeg:
      the pinned release asset, not a source build (a dry-run on a store without it lists no ffmpeg
      derivation to build)
- [ ] The shipped binary is static (no dynamic dependencies), ships no ffprobe, and its
      `ffmpeg -version` configure line shows the audio-only build: the demuxers, audio decoders,
      `flac` encoder, `flac`/`f32le`/`null` muxers, `silencedetect`/`atrim`/`aresample` filters and
      the `file` protocol — and no video codecs, hwaccels, capture devices or network protocols
- [ ] Every upload format the app accepts (the 19 extensions in `allowed-extensions.json`, using item
      61's committed fixtures) decodes to the canonical FLAC with the shipped binary
- [ ] The app's other ffmpeg uses still work with it: `f32le` PCM decode, `silencedetect`, the `atrim`
      window cut and the V10 `concat` backfill
- [ ] A registered asset that is missing or does not match its sha256 fails naming the asset, url and
      sha256; an asset made from another source build fails at evaluation naming both store paths;
      the stray-path scan fails on an unregistered path; every pinned reference is in the output's
      closure; no occurrence of the asset's build store path survives
- [ ] The ffmpeg closure in the bundle, the server package, `docker-root` and the devenv contains none
      of the video libraries the slim build drops (x265, libvpx, libaom, svt-av1, dav1d, x264); their
      sizes are measured and recorded. The bundle's closure is at or below item 61's measured size;
      any growth stops the item for the user's decision
- [ ] The shared artifact helper replaces item 56's inline Mesa code and serves both artifacts; the
      Mesa's four flake checks stay green on the refactored code, and the flake's asset builder prints
      the ffmpeg pin (the user builds and uploads the asset; agents never publish)
- [ ] (must not break) Item 61's baseline: headless ffmpeg no longer ships anywhere; the packaged
      desktop specs (bundle 11+3, AMD 12+2, NVIDIA 12+2), `nix build .`, `nix flake check`, the
      Docker image and the e2e journey stay green; the devenv health check passes and the shell's
      `ffmpeg -version` is the slim build

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
