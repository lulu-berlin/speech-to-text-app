# 80 — Documentation-only pull requests skip the heavy CI jobs

Status: in-progress
Depends on: [49](../docs/specs/windows-support.md) (adds the Windows jobs this must cover too),
[51](../docs/specs/ci-verification.md) (done; the workflow and the `CI (all checks)` aggregator)
Blocks: —

## Goal

A pull request that changes only documentation still needs its PR and the required `CI (all checks)`
check, but it should not spend 19–31 minutes building the backend, frontend, Nix closures and
desktop packages. The workflow decides once, from the PR's diff, whether code changed; the heavy
jobs are skipped when it did not, and `CI (all checks)` stays green and required. Push, schedule and
dispatch runs always run everything.

## Evidence

- `main`'s `.github/workflows/ci.yml` has more than 20 jobs; the aggregator `ci` (`CI (all checks)`,
  `ci.yml:708-722`) lists every build/test job in `needs` and fails only on a `failure` or
  `cancelled` result, so a skipped job is already accepted (the failure step checks only those two
  results).
- The branch protection on `main` now requires exactly `CI (all checks)` (verified 2026-10-23), so a
  workflow-level `paths-ignore` would leave a docs PR without the required check and block it. The
  aggregator must run.
- A docs PR runs the full matrix today: item 79's roadmap-only change would build the backend,
  frontend, both e2e suites, the Nix packages and all desktop artifacts. Item 78's docs PRs did the
  same.
- The cheap jobs that should always run: `docs-check` (`ci.yml:70-82`) and the aggregator. The
  manual asset jobs (`macos-whisper-asset`, `macos-ffmpeg-asset`) are dispatch-only and outside the
  aggregator's `needs`.
- Item 49's branch adds `Windows (gradle check)`, `Windows (pnpm test, pnpm build)` and
  `Windows (packaged zip e2e)` to the same aggregator, so this must be written for every code job
  rather than an enumerated list of today's jobs.

## Proposal

1. **A `changes` job**, first in the graph, `ubuntu-24.04`, read-only permissions:
   - `actions/checkout@v7` with `fetch-depth: 0`;
   - on `pull_request`, `git diff --name-only <base.sha> <head.sha>` and an output `code` that is
     `false` only when every path matches `docs/`, `roadmap/`, `LICENSE`, or ends in `.md`;
   - every other event (`push`, `schedule`, `workflow_dispatch`) sets `code=true`.
2. **Every job that builds, tests or packages code** gets `needs: [changes]` and
   `if: needs.changes.outputs.code == 'true'`; the manual asset jobs keep their dispatch `if` and
   stay out of the skip. `docs-check` and `ci` always run; `ci` gains `changes` in `needs`.
3. **A forced full run** for a docs PR: the `ci:full` label makes `changes` report `code=true`, so
   the same PR can run the whole matrix; a `workflow_dispatch` run always runs everything.
4. **Document it** in AGENTS § Commands and the workflow comment.

## Decided (consultation, 2026-10-23)

- **The `ci:full` label** forces the full matrix on a documentation-only PR: `changes` reports
  `code=true` when the PR carries it.
- **The documentation set** is `docs/`, `roadmap/`, any `*.md`, and `LICENSE`. Everything else is
  code, including `.github/`, `.editorconfig`, `.gitattributes`, `devenv.nix`, `flake.nix`,
  `versions.json`, `build.gradle.kts` and `stt.cmd`.
- **`codeql.yml` stays unchanged**: it is a separate workflow and is not the required check.
- **Empty or unknown diffs run everything** (`code=true`), so a missing base can never skip a job.

## Open questions (answered 2026-10-23)

- Full run on a docs PR? → **the `ci:full` label**.
- The filter's definition? → the set above; a build-affecting dotfile is code.
- `codeql.yml`? → unchanged.

## Test list (approved 2026-10-23; behaviors only)

- [x] (CI) A pull request whose diff is documentation only skips every heavy job, runs
      `Docs (exception policy)`, and `CI (all checks)` is green
- [x] (CI) A pull request touching one code path runs every heavy job (no skip)
- [ ] (CI) Changes to `.github/`, `devenv.nix`, `flake.nix`, `versions.json` or
      `build.gradle.kts` count as code, not documentation
- [ ] (CI) A push to `main` and the nightly schedule run every heavy job
- [ ] (CI) With the `ci:full` label, a documentation-only PR runs every heavy job
- [ ] (must not break) The required check keeps the name `CI (all checks)`; the aggregator's `needs`
      covers every heavy job; the manual asset jobs and the macOS-only jobs keep their conditions
- [ ] (must not break) A skipped job does not make `CI (all checks)` red, and a failing job still
      does

## e2e stance

This changes only how CI decides which jobs to run; no app feature changes, so it adds no feature
e2e (the reason is recorded here, as in item 65).

## Acceptance criteria

- A documentation-only PR merges with a green `CI (all checks)` in a couple of minutes, and a code
  PR still runs the full matrix.
- The required check's name and semantics are unchanged; no job can be skipped because of a stale or
  missing comparison (an empty or unknown diff runs everything).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, after `main`'s protection
  was tightened to require `CI (all checks)` (the earlier docs fast-track merged PRs by bypassing
  checks). Not yet discussed.
- 2026-10-23 (consultation, the user): the `changes` job and the per-job gating approved as
  proposed; a `ci:full` label forces the full matrix on a docs PR; the documentation set is
  `docs/`, `roadmap/`, `*.md` and `LICENSE` (build-affecting dotfiles are code); `codeql.yml` stays
  unchanged; empty/unknown diffs run everything. Test list approved as worded. Status:
  spec-approved.
- 2026-10-23 (cycle 1, test-list item 1): red — `node --test docs/check-ci-gating.test.mjs`
  failed at "`.github/classify-change.sh` exists: the changes job classifies its diff instead of
  guessing" (no classifier, no `changes` job, no gate). Green after adding `.github/classify-change.sh`,
  the `changes` job and `needs`/`if` on all 16 code jobs: 1 test passes, `actionlint` clean,
  `node docs/check-links.mjs AGENTS.md` clean. The GitHub job list for a documentation-only PR is
  pending the user's push.
- 2026-10-23 (cycle 2, test-list item 2): the test passed on its first run — no red was available,
  because cycle 1's classifier already defaults to `code=true` for anything that is not
  documentation; the test pins that one Kotlin path (alone and among docs) reports `code=true` and
  that every heavy job's only `if` is the `changes` output. 2 tests pass, `actionlint` clean.
  GitHub proof: this branch's own PR (its diff touches `.github/`) must show every heavy job run.
