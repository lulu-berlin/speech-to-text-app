# 0010 — Development toolchain in devenv

Status: accepted · Date: 2026-10-05

## Context
ADR 0001 makes `devenv.nix` the only source of development tools. The user instructed that any tool an agent needs for development is added to `devenv.nix` directly. Adding Node for the frontend raised two choices: which Node version to use, and whether to keep the globally installed TypeScript compiler from `languages.typescript` (`tsc` 7) alongside the project-local `typescript` the frontend will declare in `frontend/package.json`.

## Decision
- Agents add any development tool they need to `devenv.nix` themselves, commit it as its own change, and ask the user to re-enter the shell; Nix-level results are verified by the user. Tools are never installed ad hoc.
- Node: devenv's default Node package from the pinned nixpkgs (`languages.javascript.enable` + `languages.javascript.npm.enable`); no pinned LTS.
- TypeScript: keep the global `tsc` from `languages.typescript`.

## Consequences
- Node's major version follows the nixpkgs pin in `devenv.lock`; updating the lock can upgrade Node.
- Two TypeScript compilers may coexist once the frontend exists: the global `tsc` and the project-local one under `frontend/node_modules`. Frontend build and test scripts run through npm, which uses the project-local version, so the build does not depend on the global one.
- Tool additions do not wait for a roadmap consultation, but every addition is visible as its own commit.
