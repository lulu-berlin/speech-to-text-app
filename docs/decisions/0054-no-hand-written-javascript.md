# 0054 — No hand-written JavaScript: we write TypeScript, JavaScript only comes out of it

Status: accepted · Date: 2026-10-15

## Context

ADR 0001 makes the frontend TypeScript, and the e2e specs and Playwright configs are TypeScript
too. Everything else that runs on Node was written as plain JavaScript without a decision: item
50's Electron app (`desktop/*.mjs`, `preload.cjs`, the download screen's inline `<script>`, the
electron-builder config), its docker harness (`e2e/desktop/run-in-docker.mjs`), and older scripts
— the documentation checks (`docs/*.mjs`) and the e2e setup (`e2e/check-playwright.mjs`,
`e2e/global-setup.mjs`, `e2e/lib/backend.mjs`). Untyped code has already cost a defect here: a
shadowed `process` in `desktop/backend.mjs` that a type checker would have reported.

The user's rule (2026-10-15): no JavaScript code is written directly by us, only as a compilation
output of TypeScript.

The devenv and CI both pin Node 24.21.0 (`versions.json`), which runs `.ts` files directly by
stripping their types (verified 2026-10-15: a typed `.ts` file runs with plain `node`, without a
warning). Node does not type-check, and it can only strip syntax that is erasable — no enums,
namespaces or constructor parameter properties.

## Decision

- **Everything we write is TypeScript.** No `.js`, `.mjs`, `.cjs` or `.jsx` file and no inline
  `<script>` is written by hand.
- **Code that ships is compiled with `tsc`**: the Electron main process, its preload script and
  the download screen's script. The output goes to an ignored build directory, and the packages
  (the `.deb`, the flake package) take the compiled JavaScript. A browser cannot run TypeScript and
  the preload must be CommonJS, so these need a compiler regardless.
- **Scripts Node runs are `.ts` files run directly** (staging, harnesses, documentation checks, test
  setup), with erasable syntax only — `erasableSyntaxOnly` in their `tsconfig.json` enforces it.
  If a script ever gets ugly or hard to maintain because of that restriction, it is compiled with
  `tsc` instead; small scripts stay direct.
- **Types are checked, not just stripped**: each package's standard test command runs
  `tsc --noEmit` over its TypeScript, so a type error fails `pnpm test` like a format violation.
- **Configuration is data**: a tool configuration that would otherwise be a JavaScript file is
  written as JSON/YAML, or as TypeScript where the tool reads it.
- **Exception — generated third-party files** that a tool writes and that must not be edited, such
  as MSW's `frontend/mock/mockServiceWorker.js` (`msw init`), are not ours and stay as generated.
- **Migration**: roadmap item 50 converts the files it added before it merges; roadmap item
  [54](../../roadmap/54-typescript-for-the-remaining-scripts.md) converts the older ones
  (`docs/*.mjs`, `e2e/check-playwright.mjs`, `e2e/global-setup.mjs`, `e2e/lib/backend.mjs`).

## Consequences

- One language for everything we write besides Kotlin; editors and `tsc` see every call across
  the Electron app, its scripts and its tests.
- The desktop package gains a build step (`tsc`) before staging and packaging, and the Nix build
  of the desktop app compiles the same sources.
- Scripts give up enums, namespaces and parameter properties unless they move to compilation;
  imports between scripts name their `.ts` files (`allowImportingTsExtensions`).
- Until item 54 lands, the older scripts are known exceptions to this rule.
