# 63 — Stop compiling whisper-cpp on every uncached machine

Status: draft
Depends on: —
Blocks: —

## Goal

The flake's whisper-cpp (Vulkan, no SDL, pinned to the release the Gradle bootstrap's prebuilt
binary uses) is no longer compiled on every uncached machine and CI run, without changing which
whisper-cli version the Nix build, the devenv and the Gradle path run.

## Evidence

- `nix/whisper-cpp.nix` overrides nixpkgs' `whisper-cpp` with `vulkanSupport = true`,
  `withSDL = false` and the source of tag `v1.8.4` (`nix/versions.nix` `wcTag`), so its output is
  not a nixpkgs build and not in cache.nixos.org. It feeds `nix build .` (the server package and
  so the Docker image), `.#desktop`, `.#desktop-bundle` and the devenv.
- PR #9's run `38825990732` spent about eight minutes building it for `.#desktop`.
- nixpkgs `494ce7fd` (the flake's pin) has `whisper-cpp-vulkan` (`pkgs/top-level/all-packages.nix:5306`),
  but at version 1.9.2 (`pkgs/by-name/wh/whisper-cpp/package.nix:84`) and with `withSDL ? true`
  (`:32`) — the SDL example `nix/whisper-cpp.nix:15-19` cut (~330 MiB).
- The 1.8.4 pin exists so the Nix build and the Gradle bootstrap's jiang1997 1.8.4 prebuild run one
  version (item 33 consultation, 2026-10-11).

## Proposal

Open — candidates:

1. Move every path to the nixpkgs release (1.9.2), if a matching prebuild exists for the Gradle
   bootstrap, and use a cached nixpkgs output — only if one without SDL is cached.
2. A pinned prebuilt artifact through item 56's mechanism (single output, as for Mesa).
3. Accept the compile time.

## Open questions

- Is `whisper-cpp-vulkan` (with SDL) in cache.nixos.org at the pin, and how much does its SDL
  closure add compared with an artifact of our build?
- Does a 1.9.2 prebuild exist for every platform the Gradle bootstrap serves (`nix/versions.nix`)?
- Shared artifact helper with items 56 and 62?

## Test list

To be written with the user at the consultation.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request (item 61's consultation).
  Not yet discussed.
