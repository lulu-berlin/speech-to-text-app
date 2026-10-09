# 0036 — One nixpkgs pin shared by devenv and the flake

Status: accepted · Date: 2026-10-09

## Context
Item 02 ([repo skeleton and conventions](../specs/repo-skeleton-and-conventions.md)) pinned nixpkgs for the flake in `flake.lock`, while devenv resolved its own nixpkgs. Two universes of packages mean the versions a developer runs (gradle, ktlint, whisper-cpp, ffmpeg, pnpm) can drift from the versions the flake builds, and the model hashes in `devenv.nix` and `nix/models.nix` are checked against two worlds. The user decided in the item 16 consultation (2026-10-09) to share one pin.

## Decision
- The flake's locked nixpkgs input is the single pin; devenv uses the same input instead of resolving its own.
- A nixpkgs bump is one change: update the lock once, both devenv and the flake move together.

## Consequences
- Dev and build always see the same package versions; version-skew bugs become impossible by construction.
- A lock bump rebuilds the devenv as well as the flake (the user re-enters the shell and re-verifies).
- The exact wiring (how `devenv.nix` takes the flake's input) is an implementation detail of roadmap item 16, settled at its first checkpoint.
