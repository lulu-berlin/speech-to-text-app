# 51 — CI verification: first GitHub Actions workflow

Status: draft
Depends on: — (infrastructure; nothing to build on)
Blocks: — [48](48-macos-support.md) and [49](49-windows-support.md) (their CI-runner verification)

## Goal

The repository gets its first continuous-verification workflow. It runs the whole check stack
(`gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build`, the e2e journey) on every
relevant platform — Linux for day-to-day merging, macOS (ARM, and Intel per item 48) and Windows per
item 49 — with dependency caching so runs stay fast and deterministic. Filed because the macOS and
Windows items were approved with CI as their verification path: nobody on the team owns a Mac or a
Windows machine, and the repo has no CI today.

## Evidence

- No `.github/workflows` exists in the repository (verified by listing; the root has only devenv/flake
  config).
- The standard commands are defined in AGENTS.md § Commands (ready-made for a runner: `gradle check`,
  `gradle integrationTest` needs `setsid` from util-linux — present on ubuntu runners; `pnpm
  test`/`build`; the top-level e2e package) and the devenv shell pins every tool version.
- e2e and integration tests need real binaries and models: whisper-cli, ffmpeg, the CAM++ ONNX
  model, the whisper model (~1.5 GB) — heavy downloads that a workflow must cache to be usable.

## Proposal

To be settled in the consultation. Starting point:

1. A single workflow with a platform matrix: ubuntu-latest, macos-14 (ARM; Intel per 48), and
   windows-latest as 48/49 land. Run the packaged checks first (no Nix needed), with devenv/Nix
   builds as a second job once darwin works.
2. Caching: Gradle (`~/.gradle`), pnpm (`pnpm store`), and the model/tool downloads (the bootstrap
   already hashes them), so the e2e/integration jobs do not re-download multi-GB models each run.
3. Trigger policy: on every push to `main` and PRs; decide whether integration/e2e run on every
   push or are gated (they spawn real processes and take minutes); keep the unit+format gate fast.
4. Secrets: none (no deployment yet). Report trend flakiness to the known-issues backlog per its
   Maintenance rules.

## Open questions (to be answered in the consultation)

- Runner set now vs as 48/49 land (Linux-only first? Windows/macOS runners cost money).
- e2e/integration run on every push, on PRs only, or nightly?
- Nix builds in CI (flake on the same runners) or keep CI to the Gradle/pnpm paths, with Nix
  verified by the user (ADR 0010)?

## Test list (proposed; behaviors only)

- [ ] A pushed commit runs the workflow and a clean Linux job passes `gradle check` + `pnpm
      test` + `pnpm build` within a sensible time with warm caches
- [ ] The integration and e2e jobs pass on Linux (with `setsid` available)
- [ ] Caches hit on the second run (no multi-GB re-download of models/tools)
- [ ] (as 48/49 land) The macOS and Windows jobs pass per their items' test lists
- [ ] A flaky CI failure is reported to the known-issues backlog, not silently tolerated

## Acceptance criteria

- The repo has a working, cached, documented CI workflow exercising the standard commands on the
  platforms the roadmap needs; every new roadmap item's acceptance tests run somewhere automated.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed as the verification vehicle the macOS/Windows consultations asked for ("I don't
  have a Mac", "maybe we can use CI"); depends on nothing else. Status: draft.