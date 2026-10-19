# 61 — Ship nixpkgs' cached headless ffmpeg

Status: done (roadmap item 61, approved 2026-10-18)
Depends on: —
Blocks: [62](audio-only-ffmpeg.md)

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

- [x] On x86_64-linux, the desktop (`.#desktop`, and so `.#desktop-bundle`), the server package
      (`nix build .`, and so `.#docker-root`) and the devenv shell get the same ffmpeg: nixpkgs'
      `ffmpeg_8-headless`, substituted from cache.nixos.org, not built (a dry-run build on a store
      without it lists no ffmpeg derivation to build)
- [x] The GStreamer/GTK-4 group (python3, gtk4, gst-plugins-bad, flite, pipewire) is not reachable
      from ffmpeg in the bundle's, the server package's or the Docker image's closure
      (`nix why-depends` from ffmpeg finds none)
- [x] The bundle's closure size before and after is measured and recorded; growth above 50 MiB
      stops the item for the user's decision. The server package's and `docker-root`'s closures
      are measured and recorded too
- [x] Every upload format the app accepts (the 19 extensions in `allowed-extensions.json`,
      including the video containers and `amr`, `wma`, `aif`/`aiff`) decodes to the canonical
      FLAC with the shipped ffmpeg
- [x] The devenv's own check (`ffmpeg -version` in its health check) still passes; the loss of
      `ffplay` in the shell is documented
- [x] The versions manifest still says ffmpeg 8.1 (`versions.json` unchanged, so the Gradle
      bootstrap's bytedeco 8.1.2 stays in sync), and `nix flake check` passes
- [x] (must not break) the packaged desktop specs (bundle 11+3, bundle AMD 12+2, bundle NVIDIA
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

- 2026-10-18 (cycle 1, test-list item 1, the user's runs): red at `3d614743` —
  `nix build -L .#checks.x86_64-linux.ffmpeg-headless` failed at evaluation, "the desktop ships
  /nix/store/3jcvmrfw…-ffmpeg-8.1.2-bin, not nixpkgs' /nix/store/zbcxlfh8…-ffmpeg-headless-8.1.2-bin";
  the empty-store dry-run listed `/nix/store/px55dlf7…-ffmpeg-8.1.2.drv` under "will be built". Green
  at `0e68d30b`: the check built and passed in 7 s (no ffmpeg built); the empty-store dry-run printed
  no ffmpeg; the devenv's `ffmpeg` resolves to the same
  `/nix/store/zbcxlfh8…-ffmpeg-headless-8.1.2-bin/bin/ffmpeg`, version 8.1.2. The check's first
  version (`3d614743`) failed the server package's evaluation assertion because `directInputs`
  (`getContext` over `drvAttrs`) did not report the headless bin path; the server package is now
  asserted from the built wrapper (`0e68d30b`). Item 3's "before" closures at `3d614743`:
  `.#default` 2.3 GiB, `.#desktop-bundle` 2.0 GiB, `.#docker-root` 3.1 GiB; "after" at `0e68d30b`:
  1.8, 1.7 and 2.8 GiB — all smaller, so the 50 MiB bundle guard did not trigger. Item ticked.

- 2026-10-18 (refactor after cycle 1, approved): bound the headless package once in `flake.nix`
  (`ffmpeg = pkgs.ffmpeg_8-headless;`) for the versions manifest, the wrapper's PATH and the
  check; `ffmpeg-headless` and `versions-sync` green in 2 s at `9c73795c`, derivations unchanged.

- 2026-10-18 (cycle 2, test-list items 2 and 3, the user's runs): red measured on the pre-switch
  ffmpeg (stock `ffmpeg_8`, `/nix/store/ss9dmg3n…`): its closure held `python3-3.14.7`,
  `flite-2.2-lib`, `pipewire-1.6.9`, `gst-plugins-bad-1.28.7`, `gtk4-4.22.4`, and
  `nix why-depends` showed ffmpeg → sdl2-compat → sdl3 → pipewire → gstreamer → python3. New check
  `checks.x86_64-linux.ffmpeg-no-gstreamer-gtk` (`a86e4c28`, narrowed in `ddac51a9`) greps the
  shipped ffmpeg's closure for the five name prefixes; green — the grep is empty,
  `nix why-depends <headless ffmpeg> <python3>` answers "does not depend on", and
  `nix path-info -r .#desktop-bundle .#default` holds none of the five. The first version checked
  the whole package closures and failed on `docker-root → nixpkgs Mesa → python3`; that referrer
  is unrelated to ffmpeg and the item's behavior is reachability from ffmpeg, so the check was
  narrowed. `docker-root`'s only member is that Mesa `python3` (not reachable from ffmpeg).
  Item 3 needed no further run: the before/after sizes in cycle 1 are the measurement (bundle
  2.0 → 1.7 GiB, a decrease), so it is ticked as well.

- 2026-10-18 (cycle 3, test-list item 4, approved method A): 19 fixtures committed at
  `backend/src/test/resources/upload-formats/` (268 KiB) with `generate.sh` — 18 generated with
  ffmpeg 8.1.2 from the pin; `sample.amr` (838 bytes) generated by the user with `ffmpeg_8-full`,
  the only variant with `libopencore_amrnb`. New Integration spec `UploadFormatsSpec` runs every
  fixture through the real `AudioChunker` and asserts the canonical `flac/s16/16000/1` clip; green
  on arrival as approved (headless and small share every decoder). Harness validation stopped at
  the then-missing `sample.amr` after eleven fixtures; with it,
  `gradle integrationTest --tests 'stt.audio.UploadFormatsSpec'` passed (1 test, 0 failures;
  decoded durations 0.464–0.534 s, AMR 0.520 s). Item ticked.

- 2026-10-18 (cycle 4, test-list item 5): the health check ran green in the user's `devenv shell`
  runs at `0e68d30b` and `9c73795c` (its `devenv:enterShell` runs `ffmpeg -version`), and the
  shell's ffmpeg is the headless `/nix/store/zbcxlfh8…-ffmpeg-headless-8.1.2-bin`. README
  § Development environment now states that the shell's ffmpeg is that same headless variant and
  has no `ffplay`; `node docs/check-links.mjs README.md docs/architecture.md` passes. The user's
  `devenv shell -- sh -c 'ffmpeg -version | head -1'` on the rebased tree is the final confirmation.
  Item ticked.

- 2026-10-18 (cycle 5, test-list item 6, the user's runs on the rebased `5848f0ac`): `versions.json`
  is unchanged from `main` (empty `git diff main -- versions.json`; the manifest still renders 8.1
  from the headless variant's 8.1.2), and `nix flake check --print-build-logs` passed in 3m19s —
  it rebuilt the backend (its `gradle check` green), `stt-app`, `stt-desktop`, the closure-info
  checks and both new ffmpeg checks. The same run's `devenv shell` health check printed
  `ffmpeg version 8.1.2`, confirming item 5 on the final tree. Item ticked.

- 2026-10-18 (cycle 6, test-list item 7, the user's runs at `4c3e8210` — the pre-rebase tree of
  `7a3e40df`, differing only in `main`'s later `roadmap/` and `docs/`): `nix build .` succeeded;
  `pnpm --dir e2e test:desktop:nix` **11 passed, 3 skipped**; `test:desktop:bundle` **11 passed,
  3 skipped** (bundle `/nix/store/ck626gkp…-stt-app-bundle`); `test:desktop:bundle:amd`
  **12 passed, 2 skipped** (spec 10 on the Radeon 680M); `test:desktop:bundle:nvidia`
  **12 passed, 2 skipped** (spec 11 on the RTX 3050). CI on the PR (`7a3e40df`, run
  [39153800701](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39153800701))
  succeeded, every job green — `Nix (build)`, `Nix (flake check)`, the flake-package desktop
  e2e, the bundle e2e, the `.deb` e2e, backend check/integrationTest, frontend, aggregator.
  Item ticked; status in-review.

- 2026-10-18 (review correction): the refactor commit `dbab9166` ("share the canonical stream check
  between the audio specs") was approved at its cycle's refactor checkpoint; the cycle-3 entry above
  omitted the approval line.
- 2026-10-18: main-tree review approved by the user. Two findings move to item 62 by the user's
  decision: `UploadFormatsSpec` runs `ffmpeg`/`ffprobe` from `PATH`, so CI's `backend-integration` job
  does not test the shipped binary (the devenv, where it is the shipped headless variant, does), and
  its extension list is hardcoded instead of read from `allowed-extensions.json`; 62's safety net
  must run the shipped binary and validate without `ffprobe` (its build has none). Spec moved to
  `docs/specs/headless-ffmpeg.md` with `Status: done`, squashed to one commit on top of `main`
  (ADR 0050).
