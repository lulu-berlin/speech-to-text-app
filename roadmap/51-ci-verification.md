# 51 — CI verification: first GitHub Actions workflow

Status: spec-approved
Depends on: — (infrastructure; nothing to build on)
Blocks: — [48](48-macos-support.md) and [49](49-windows-support.md) (their CI-runner verification)

## Goal

The repository gets its first continuous-verification workflow. It runs the whole check stack
(`gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build`, the e2e journey) with
dependency caching so runs stay fast and deterministic. Linux runs on every push; macOS and Windows
legs run at a lower cadence (nightly) once their platform items land. macOS verification includes
the Nix flake build; Windows has no Nix (Nix does not support Windows). Filed because the macOS and
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
- Item 48's Nix-darwin verification ("`nix build`/`nix run` and the devenv shell work on darwin")
  has no local Mac to run on; a macOS runner with Nix is the automated home for it.

## Decided (consultation, 2026-10-13)

- **Matrix order**: a Linux-first workflow now; the macOS (ARM; Intel per item 48) and Windows matrix
  legs are added as 48/49 land.
- **Cadence**: every push (to `main` and PRs): Linux unit + format + pnpm + integration + e2e.
  Lower cadence (nightly scheduled runs) for the macOS and Windows legs; their 48/49 PRs can trigger
  their own leg on demand.
- **Nix**: macOS CI legs run the flake build (`nix build`/`nix run` and the devenv shell) — that is
  item 48's Nix-darwin verification. No Nix job on Windows (Nix has no Windows support). Linux CI is
  not required to use Nix (the Gradle/pnpm/bootstrap path is sufficient and matches ADR 0010's
  user-verified Nix split for Linux).

## Proposal

1. A single workflow: an every-push Linux job tree (unit+format fast gate, then integration and e2e)
   and a nightly scheduled job running the macOS/Windows legs once they exist. Platform legs guarded
   by `if` so the workflow is green on Linux before 48/49 land.
2. Caching: Gradle (`~/.gradle`), pnpm (`pnpm store`), and the model/tool downloads (the bootstrap
   already hashes them), so no multi-GB re-download on any leg.
3. macOS legs run the Nix flake build on a darwin runner (aarch64 first, Intel per 48).
4. Secrets: none (no deployment yet). Report trend flakiness to the known-issues backlog per its
   Maintenance rules.

## Open questions

- Exact nightly schedule (time, timezone) and whether 48/49 PRs add their own leg triggers — settle
  at the first test item.
- Whether the Linux fast gate runs on every push and integration/e2e only on `main` merges, or all
  three on every push (cost vs coverage; settle at the first test item).

## Test list (proposed; behaviors only)

- [ ] A pushed commit runs the workflow and the Linux fast gate passes `gradle check` + `pnpm
      test` + `pnpm build` with warm caches in a sensible time
- [ ] The Linux integration and e2e jobs pass on every push (with `setsid` available)
- [ ] Caches hit on the second run (no multi-GB re-download of models/tools)
- [ ] (as 48/49 land) The nightly macOS and Windows legs pass their items' test lists, and a 48/49
      PR can trigger its own platform leg
- [ ] (mac CI) `nix build`/`nix run` and the devenv shell pass on the macOS runner (item 48's
      Nix-darwin verification); no Nix job exists on Windows
- [ ] A flaky CI failure is reported to the known-issues backlog, not silently tolerated

## Acceptance criteria

- The repo has a working, cached, documented CI workflow exercising the standard commands: Linux on
  every push, macOS/Windows on a lower cadence as their items land, Nix verified on macOS, none on
  Windows. Every new roadmap item's acceptance tests run somewhere automated.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed as the verification vehicle the macOS/Windows consultations asked for ("I don't
  have a Mac", "maybe we can use CI").
- 2026-10-13: consultation — Linux-only matrix first until 48/49 land; e2e/integration on every push
  for Linux, lower (nightly) cadence for macOS/Windows; Nix builds in CI on macOS, no Nix on
  Windows. Status: spec-approved.