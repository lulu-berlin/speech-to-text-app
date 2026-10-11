# 39 — devenv/doc drift: the playwright version comment (1.61.1 vs 1.63.0)

Status: spec-approved
Depends on: [16](../docs/specs/nix-flake-package.md) (done; the downgrade update moved the devenv's playwright to 1.63.x)
Blocks: —

## Goal

The `devenv.nix` comment about the e2e Playwright version matches what the devenv actually provides and what `e2e/package.json` requires; a mismatched e2e installation fails with a clear message instead of an "Executable doesn't exist" error and a hang.

## Already decided

- Found 2026-10-11 by the item 36 workspace agent; filed by the root agent after consultation.
- Evidence: `devenv.nix`'s e2e comment (~lines 44–47) pins the story to "@playwright/test 1.61.1, browser revisions 1228", but item 16's devenv update moved the realized `playwright-driver.browsers` bundle to **chromium-1243 (playwright 1.63.x)**, and `e2e/package.json` requires `@playwright/test` 1.63.0 (item 16 bumped it to match).
- Impact: a stale e2e install (1.61.1) fails with `Executable doesn't exist … chromium-1228`, and `npx playwright install` hangs against the read-only nix path; `pnpm install` (in `e2e/`) plus the store's browsers fixes it.

## Proposal

To be settled in the consultation. Starting points: correct the `devenv.nix` comment to the real version (the realized playwright and its browser revision); make the mismatch obvious at run time (the check could compare the resolved `@playwright/test` against the comment or fail early with a clear line about `pnpm install`).

## Open questions (answered 2026-10-11)

- How to handle this finding? → **Its own minimal item** (user; like the backend findings, filed as roadmap item 39).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [ ] The `devenv.nix` comment names the playwright version and browser revision the devenv actually provides, and it matches `e2e/package.json`'s `@playwright/test` requirement
- [ ] A fresh `pnpm install` in `e2e/` resolves `@playwright/test` to the version the comment names, and the store's browsers at the named revision exist
- [ ] (must not break) The e2e package's own gates (prettier + the Playwright run on the user's machine) stay green

## Acceptance criteria

- The user's e2e runs work from the documented state without "Executable doesn't exist" or a hanging install; the comment and the requirement agree.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: filed from the item 36 workspace's findings; consultation: own minimal item. Test list approved; status `spec-approved`.