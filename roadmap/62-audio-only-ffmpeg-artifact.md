# 62 — An audio-only ffmpeg, shipped as a pinned prebuilt artifact

Status: draft
Depends on: [56](../docs/specs/desktop-bundle-mesa-artifact.md) (the pinned-release pattern),
[61](61-headless-ffmpeg.md) (the headless baseline this item slims)
Blocks: —

## Goal

The flake ships an ffmpeg built for what the backend does — decode an uploaded audio or video file's
audio track, `silencedetect`/`atrim`, write FLAC — without the video codecs, subtitle rendering,
network protocols, capture devices and GPU paths `ffmpeg_8-headless` carries. Being a custom build
it is not in cache.nixos.org, so on Linux x86_64 it comes from a versioned, sha256-pinned GitHub
Release asset, the way item 56 brings the bundle's Vulkan-only Mesa; a system without an asset
builds it from source.

## Evidence

- Item 61's measurement (2026-10-18, cache.nixos.org narinfo, uncompressed NAR): the headless
  `bin` closure is 305.1 MiB, 115 paths. Its `lib` output's direct references include the video
  and extra-feature libraries x265 23.8, libvpx 8.6, libaom 8.4, svt-av1 7.5, dav1d 2.5, x264 2.4,
  xvidcore, libtheora, openjpeg, libwebp, zimg, libass (+ fontconfig, freetype, harfbuzz,
  fribidi), libbluray, srt, librist, libssh, gnutls, v4l-utils, libva-minimal, vulkan-loader and
  ocl-icd.
- Keeping only the references an audio path can use (glibc, bzip2, xz, libopus, libvorbis, soxr,
  speex, lame, ffmpeg's data output) leaves a 16-path, 51.7 MiB closure, of which glibc is
  33.9 MiB — plus ffmpeg's own outputs (35.2 MiB today, smaller without the video codecs).
- ffmpeg decodes Opus, Vorbis, MP3, AAC and FLAC natively; LAME only encodes MP3, which the
  backend never does. `[INFERENCE]` the external audio libraries may be droppable too.
- The upload accepts 19 extensions (`backend/src/main/resources/allowed-extensions.json`): `mp3
  wav flac ogg oga opus m4a aac wma aif aiff amr webm mp4 m4v mov mkv mka 3gp`. The video
  containers need their demuxers kept even though no video is decoded.
- `ffmpeg_8` is multi-output (`bin`, `lib`, `dev`, `doc`, `man`, `data`, `out`;
  nixpkgs `494ce7fd` `pkgs/development/libraries/ffmpeg/generic.nix:1039-1050`), and `bin`
  references `lib` and `data`: item 56's re-link handles one output path, this needs three — or a
  build that is one output.

## Proposal

1. Define the audio-only build: the decoders, demuxers, filters (`silencedetect`, `atrim`,
   resampling) and the FLAC encoder the backend uses, everything else off.
2. Ship it through item 56's mechanism: a flake package that builds the asset and prints its pin,
   an entry in `nix/desktop-artifacts.nix` (or a renamed artifacts map), a fixed-output fetch that
   fails naming the asset, re-linking into this flake's nixpkgs, a stray-path scan and the
   evaluation-time stale-pin gate.
3. Use it wherever item 61 put `ffmpeg_8-headless`.

## Open questions

- How narrow is the build: nixpkgs' `with*`/`build*` flags only, or ffmpeg's own
  `--disable-everything` with an explicit enable list? Which external libraries stay?
- One output (a static or single-output build) or the three outputs re-linked?
- One shared Nix helper for both artifacts (Mesa and ffmpeg), or a second file in item 56's shape?
- Release tag per artifact (`desktop-ffmpeg-v1`) or one artifacts release?
- Does the devenv take the artifact too, or keep item 61's headless ffmpeg?
- How is "every accepted format still decodes" proven: fixtures per extension (some — `amr`,
  `wma` — need encoders the slim build lacks), committed or generated with another ffmpeg?

## Test list

To be written with the user at the consultation.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request, split from item 61 at its
  consultation (the user: item 61 switches to the cached headless ffmpeg; this item produces a
  really slimmed-down audio-only build, which needs a build artifact). Not yet discussed.
