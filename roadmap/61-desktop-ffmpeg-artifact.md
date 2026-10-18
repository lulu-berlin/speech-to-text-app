# 61 — Prebuild the desktop's ffmpeg for Linux x86_64

Status: draft
Depends on: [56](../docs/specs/desktop-bundle-mesa-artifact.md) (the pinned-release pattern this item
reuses)
Blocks: —

## Goal

The desktop packages stop compiling their ffmpeg from source on each uncached machine and CI run: on
Linux x86_64 the override comes from a versioned, sha256-pinned GitHub Release asset, the way item 56
brings the bundle's Vulkan-only Mesa. A system without an asset keeps the source build.

## Evidence

- `nix/desktop.nix:124-127` overrides `pkgs.ffmpeg_8` with `withSdl2 = false` and
  `buildFfplay = false` (the comment above it measures why: with whisper-cpp's SDL example also off,
  the GStreamer/GTK-4 cluster leaves the closure). The override is not stock `ffmpeg_8`, so it is not
  in cache.nixos.org.
- `.#desktop` links it into its tools (`nix/desktop.nix:128-132`), and `.#desktop-bundle` is built
  from `.#desktop` (`flake.nix`, `desktop-bundle = import ./nix/desktop-bundle.nix {inherit pkgs
  desktop;}`), so both compile it.
- Item 56's final run (2026-10-18): the user's 17m14s `nix build .#desktop-bundle` rebuilt this
  ffmpeg; with Mesa prebuilt it is the largest remaining compile in that build.

## Proposal

Item 56's mechanism (`nix/desktop-mesa.nix`, `nix/desktop-artifacts.nix`): the output as a
deterministic `.tar.zst`, built by a flake package that prints its pin, uploaded by the user as a
release asset; a fixed-output fetch with a named-asset failure; re-linked into this flake's nixpkgs
(its own store path rewritten, its direct references pinned as inputs, a stray-path scan); an
evaluation-time stale-pin gate; an `ffmpeg.<nix system>` entry in the same map.

## Open questions

- Which outputs ship: `ffmpeg_8` is multi-output (`bin`, `lib`, `dev`, …) and item 56's pattern
  handles one. Does the asset carry only what the desktop tools reach (`bin` and `lib`), and how is
  the cross-output reference (`bin` → `lib`) re-linked?
- One shared Nix helper for both artifacts, or a second file in item 56's shape?
- Does the custom `whisper-cpp` (`nix/whisper-cpp.nix`, about eight minutes in PR #9's run
  `38825990732`) belong here or in its own item?
- Release tag per artifact (`desktop-ffmpeg-v1`) or one desktop-artifacts release?

## Test list (proposed; behaviors only)

- [ ] On x86_64-linux the desktop's ffmpeg is the pinned asset, and the source ffmpeg is not in the
      bundle's build graph
- [ ] A registered asset that is missing or does not match its sha256 fails, naming the asset, its
      url and its sha256, and never falls back to compiling
- [ ] An asset made from another source build (nixpkgs bumped, the override edited) fails at
      evaluation, naming the asset and both store paths
- [ ] A system without an entry builds ffmpeg from source
- [ ] The prebuilt ffmpeg names no store path besides itself and its pinned references, and its
      closure contains every pinned reference
- [ ] (must not break) the packaged desktop specs (`.deb`, bundle, bundle AMD/NVIDIA) and
      `nix flake check`

e2e-exempt (ADR 0046): a packaging item that changes no app feature; the packaged desktop specs are
its end-to-end proof.

## Acceptance criteria

- `nix build .#desktop-bundle` on an empty store fetches ffmpeg instead of compiling it on
  x86_64-linux.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request, from item 56's follow-up
  (its final Approval log entry). Not yet discussed.
