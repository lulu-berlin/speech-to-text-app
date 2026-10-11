# 0046 — Every app feature has an end-to-end test

Status: accepted · Date: 2026-10-11

## Context

The unit and integration suites were green while the real app was broken in ways only a browser against the real backend shows: uploads never started (the engine was not woken), finished tasks' audio returned 500, the seek bar jumped and could not seek past the first chunk, and a silent stream stayed "Live". Item 21 (the Playwright end-to-end suite in `e2e/`, real backend, real models) found or confirmed several of these; the rest the user found by hand (items 29 and 30).

## Decision

- From roadmap item 31 on, every item that adds or changes an app feature (anything a user can see or do in the browser, or an API behavior the frontend relies on) includes at least one end-to-end test of that feature in the `e2e/` Playwright suite, against the real backend.
- The e2e test is part of the item's test list (Canon TDD step 1) and part of its approval: an item is not `in-review` without it, and the diff review includes the e2e run's result.
- Items that change no app feature (build, packaging, toolchain, pure refactors) are exempt; their item states why.
- The e2e suite runs on the user's machine (Playwright's browser comes from `devenv.nix`; the sandbox has none), so an item's e2e result is recorded from the user's run.

## Consequences

- New feature items depend on item 21, which delivers the `e2e/` package and its harness.
- Each feature item's acceptance criteria name its e2e scenario; the root agent's review asks for the user's e2e run output.
- AGENTS.md § Testing states the rule.
