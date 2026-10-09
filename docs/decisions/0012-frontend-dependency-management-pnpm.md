# 0012 — Frontend dependency management: pnpm

Status: accepted · Date: 2026-10-05 · Amended: 2026-10-09 (item 20)

## Context
ADR 0010 makes devenv the only source of development tools. The frontend (ADR 0001) needs a package manager, and the Nix flake deliverable must install frontend dependencies hermetically. The consultation in roadmap item 02 asked npm vs pnpm.

## Decision
- Frontend package manager: **pnpm**. `frontend/` uses pnpm exclusively; `pnpm-lock.yaml` is committed.
- Development tool: `pkgs.pnpm_11` in `devenv.nix` (own commit, per ADR 0010; pinned to a major in item 20, it was the unpinned `pkgs.pnpm` before). npm stays available in devenv for other tooling but is not used in `frontend/`.
- **Same pnpm major in dev and packaging (amended 2026-10-09, item 20).** `devenv.nix` and `flake.nix` name the same versioned nixpkgs attribute (`pnpm_11` today), never the unversioned `pkgs.pnpm`, so a nixpkgs bump cannot move development to a pnpm major the flake does not build with. A major upgrade changes both files together, in one change set, with the lockfile it produces.
- Packaging: the flake builds the frontend with nixpkgs' `fetchPnpmDeps` (fixed-output pnpm-store derivation) plus `pnpmConfigHook`/`pnpmBuildHook`, with `sourceRoot`/`pnpmRoot` pointing at `frontend/`.
- Agent-side verification installs of pnpm are session-only and never the real provision (they die with the sandbox tmpfs HOME).

## Consequences
- One package manager and one lockfile across dev and Nix, per nixpkgs guidance.
- The lockfile format is tied to a pnpm major version; nixpkgs pins store formats per pnpm major (`pnpm_10`, `pnpm_11`, ...), so the pinned nixpkgs pnpm must stay compatible with the committed `pnpm-lock.yaml`.
- A pnpm major upgrade requires re-verifying the flake's `fetchPnpmDeps` setup, and changes `devenv.nix` and `flake.nix` together.
