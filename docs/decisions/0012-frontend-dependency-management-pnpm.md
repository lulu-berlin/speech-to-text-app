# 0012 — Frontend dependency management: pnpm

Status: accepted · Date: 2026-10-05

## Context
ADR 0010 makes devenv the only source of development tools. The frontend (ADR 0001) needs a package manager, and the Nix flake deliverable must install frontend dependencies hermetically. The consultation in roadmap item 02 asked npm vs pnpm.

## Decision
- Frontend package manager: **pnpm**. `frontend/` uses pnpm exclusively; `pnpm-lock.yaml` is committed.
- Development tool: `pkgs.pnpm` added to `devenv.nix` (own commit, per ADR 0010). npm stays available in devenv for other tooling but is not used in `frontend/`.
- Packaging: the flake builds the frontend with nixpkgs' `fetchPnpmDeps` (fixed-output pnpm-store derivation) plus `pnpmConfigHook`/`pnpmBuildHook`, with `sourceRoot`/`pnpmRoot` pointing at `frontend/`.
- Agent-side verification installs of pnpm are session-only and never the real provision (they die with the sandbox tmpfs HOME).

## Consequences
- One package manager and one lockfile across dev and Nix, per nixpkgs guidance.
- The lockfile format is tied to a pnpm major version; nixpkgs pins store formats per pnpm major (`pnpm_10`, `pnpm_11`, ...), so the pinned nixpkgs pnpm must stay compatible with the committed `pnpm-lock.yaml`.
- A pnpm major upgrade requires re-verifying the flake's `fetchPnpmDeps` setup.
