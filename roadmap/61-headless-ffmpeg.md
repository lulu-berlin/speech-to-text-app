# 61 — Ship nixpkgs' cached headless ffmpeg

Status: spec-approved
Depends on: —
Blocks: [62](62-audio-only-ffmpeg-artifact.md)

## Goal

Every ffmpeg the flake ships — the desktop (`.#desktop`, and so `.#desktop-bundle`), the server
package (`nix build .`, and so the Docker image's `.#docker-root`) and the devenv shell — is
nixpkgs' `ffmpeg_8-headless`, which cache.nixos.org serves. The desktop's override stops being
compiled on every uncached machine and CI run, and no package grows by much.

## Evidence

- `nix/desktop.nix:124-127` overrides `pkgs.ffmpeg_8` with `withSdl2 = false` and
  `buildFfplay = false`. The comment above it (`:116-122`) records why: those two flags are the
  ffmpeg entry to the GStreamer/GTK-4 cluster. The override is not a nixpkgs output, so it is not
  in cache.nixos.org: item 56's final run (2026-10-18) recorded a 17m14s `nix build .#desktop-bundle`
  that rebuilt it.
- The server package (`flake.nix:264`) and the devenv (`devenv.nix:24`) use stock `pkgs.ffmpeg_8`
  (the "small" variant), which is cached but carries SDL2 and ffplay; the Docker image is the
  closure of `docker-root`, which holds the server package (`Dockerfile:4,34`).
- nixpkgs `494ce7fd` (the flake's pin), `pkgs/development/libraries/ffmpeg/`: `ffmpeg_8-headless`
  exists (`default.nix:61`). Small and headless differ only in Pulse input, SDL2, ffplay, VDPAU
  and full vs minimal libva (`generic.nix:130,138,159,211,973`); the backend runs the ffmpeg CLI
  only (decode to PCM, `silencedetect`/`atrim`, FLAC out).
- Hydra eval 1829853 (nixos/unstable at `494ce7fd`) built both: `ffmpeg_8-headless` build
  347318876 (`bin` `/nix/store/zbcxlfh8…-ffmpeg-headless-8.1.2-bin`) and `ffmpeg_8` build
  347318873 (`bin` `/nix/store/ss9dmg3n…-ffmpeg-8.1.2-bin`), both succeeded.
- Closures of the `bin` outputs, from cache.nixos.org's narinfo (uncompressed NAR sizes,
  2026-10-18): headless **305.1 MiB, 115 paths** (ffmpeg's own outputs 35.2 MiB); small
  **990.6 MiB, 287 paths**. The 175 paths only small has (720.7 MiB) lead with python3 136.6,
  flite 60.5, gtk+3 42.0, icu4c 39.7, freepats 31.8, gtk4 30.7, gst-plugins-bad 17.3 and pipewire
  16.9 MiB. Headless still carries video codecs the backend never uses: x265 23.8, libvpx 8.6,
  libaom 8.4, svt-av1 7.5, dav1d 2.5, x264 2.4 MiB (about 53 MiB; item 62 removes them). The
  override's closure was not measurable without a Nix store; it keeps Pulse, VDPAU and full libva,
  so it should not be smaller than headless's.
- The upload accepts 19 extensions (`backend/src/main/resources/allowed-extensions.json`),
  including the video containers `mp4 m4v mov mkv webm 3gp`; headless keeps ffmpeg's native
  demuxers and decoders.

## Decisions (consultation, 2026-10-18)

- **Split**: this item switches to the cached headless variant for build time; the slimmed
  audio-only build, which needs a pinned artifact, is item 62.
- **Scope**: the desktop, the server package (and so the Docker image) and the devenv — one ffmpeg
  everywhere; developers lose `ffplay` from the shell.
- **Size guard**: the closures are measured before and after and recorded; if the bundle's closure
  grows by more than 50 MiB, the item stops for the user's decision.

## Test list (approved 2026-10-18; behaviors only)

- [ ] On x86_64-linux, the desktop (`.#desktop`, and so `.#desktop-bundle`), the server package
      (`nix build .`, and so `.#docker-root`) and the devenv shell get the same ffmpeg: nixpkgs'
      `ffmpeg_8-headless`, substituted from cache.nixos.org, not built (a dry-run build on a store
      without it lists no ffmpeg derivation to build)
- [ ] The GStreamer/GTK-4 group (python3, gtk4, gst-plugins-bad, flite, pipewire) is not reachable
      from ffmpeg in the bundle's, the server package's or the Docker image's closure
      (`nix why-depends` from ffmpeg finds none)
- [ ] The bundle's closure size before and after is measured and recorded; growth above 50 MiB
      stops the item for the user's decision. The server package's and `docker-root`'s closures
      are measured and recorded too
- [ ] Every upload format the app accepts (the 19 extensions in `allowed-extensions.json`,
      including the video containers and `amr`, `wma`, `aif`/`aiff`) decodes to the canonical
      FLAC with the shipped ffmpeg
- [ ] The devenv's own check (`ffmpeg -version` in its health check) still passes; the loss of
      `ffplay` in the shell is documented
- [ ] The versions manifest still says ffmpeg 8.1 (`versions.json` unchanged, so the Gradle
      bootstrap's bytedeco 8.1.2 stays in sync), and `nix flake check` passes
- [ ] (must not break) the packaged desktop specs (bundle 11+3, bundle AMD 12+2, bundle NVIDIA
      12+2), the flake-package desktop e2e (`desktop-nix-e2e`), `nix build .` and CI

e2e-exempt (ADR 0046): a packaging item that changes no app feature; the packaged specs above are
its end-to-end proof.

## Acceptance criteria

- No ffmpeg derivation is built for any package or the devenv on an x86_64-linux store that can
  reach cache.nixos.org.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request, from item 56's follow-up
  (a pinned-release artifact for the bundle's ffmpeg override).
- 2026-10-18 (consultation): the root agent measured the variants (Evidence above); the user split
  the work — this item switches to nixpkgs' cached `ffmpeg_8-headless`, and the audio-only build
  with an artifact becomes item 62. User decisions: scope desktop + server package + devenv; the
  bundle's closure may grow by at most 50 MiB before the item stops; the custom whisper-cpp's
  compile time is filed as draft item 63. Test list approved as written above; status
  spec-approved.
