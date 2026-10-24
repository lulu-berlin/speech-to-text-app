# 82 — A red CI job is diagnosable from CI alone, and a check job does not fail for the network

Status: draft
Depends on: —
Blocks: —

## Goal

When CI fails, the failure can be diagnosed from the workflow run itself: the failing spec's trace,
screenshot and error context are attached to the run, and a runner that cannot reach a third-party
host fails only the jobs that genuinely need that host — never the lint or unit-test jobs. The
remaining flaky observations become either deterministic or explicitly recorded, instead of being
re-reported by hand.

This is the umbrella for the CI friction seen on 2026-10-23. The specific bugs stay in their own
items; this one carries the infrastructure they all need.

## Evidence

- **Failing e2e runs keep nothing.** Playwright is configured with `trace: 'retain-on-failure'`
  (`e2e/playwright.config.ts:24`) and `reporter: 'list'` (`:14`), so `test-results/` holds a trace,
  a screenshot and `error-context.md` for every failure — and no workflow step uploads it. The only
  `actions/upload-artifact` steps in `.github/workflows/ci.yml` are the DMG (`:343`), the whisper-cli
  asset (`:394`) and the ffmpeg asset (`:461`); the e2e job (`:102-127`), the desktop packaged jobs
 (`:475`, the packaged .deb job) and the macOS e2e job upload nothing. Every diagnosis of a red e2e job in this
  session (#212, #216, #223, #236, #239, #246) needed the owner to paste the console log, and the
  traces that would have settled the question were discarded with the runner.
- **A check job failed because a runner could not reach github.com.** Run #246 (`docs/known-issues-e2e-flakes`,
  a Markdown-only change) failed `macOS (Nix build + devenv)` in its `actionlint the workflows` step
  (job 120645940071) without actionlint ever running:
  ```
  Building whisper-cpp-1.8.4-aarch64-darwin.tar.gz
  desktop artifact … missing: curl: (7) Failed to connect to github.com:443 after 57 ms
  ✖ Evaluating shell in 30.3s (failed) → Failed to realize shell derivation: 1 dependency failed
  ```
  The shell realizes the pinned whisper-cpp/ffmpeg assets, which are GitHub releases of this repo,
  so `devenv shell` needs egress to github.com. The Linux twin (`Nix (devenv shell)`) ran the same
  step in the same run and passed, and `actionlint .github/workflows/*.yml` is clean locally with the
  pinned 1.7.12.
- **A failed cold shell build leaves the cache cold.** The same job logged both a primary-key miss and
  a `restore-prefixes-first-match: nix-macOS-` miss ("Could not find a cache"), then skipped the
  cache post-step (`outcome=skipped`) because the job failed. `nix-toolchain/action.yml` passes
  `save: ${{ inputs.save-cache }}`; a failure therefore saves nothing, so the next attempt starts
  cold again and retries the same downloads.
- **The same run showed the observability gap for e2e.** `E2E (Playwright)` (job 120645846612) and
  `macOS (e2e)` failed too (10:58 and 14:25 in `gradle e2e`); their logs require a token and their
  traces were not kept.
- **Leftover timing assumptions already recorded:** the packaged `.deb` spec's `finally` reads
  `app.process()` after the app exited and throws `TypeError` (`e2e/desktop-tests/13-second-quit-during-shutdown.spec.ts:33`;
  run #212, and the MED row in `docs/known-issues.md`), `59-row-bars`'s bar count (the row on
  `docs/known-issues-e2e-flakes`), and `ProcessRunnerSpec`'s wall-clock pause assertion (run #236,
  `ProcessRunnerSpec.kt:516`/`:712`). Item 65 owns `59-row-color`; item 81 owns the dropped click.

## Proposal

1. **Keep what a failure produces.** Add an `if: failure()` upload of `test-results/` (Playwright's
   output dir) to every job that runs a browser or desktop suite: `Linux (e2e)`, `macOS (e2e)`,
   the desktop packaged/bundle/flake jobs, and the Windows packaged zip job once item 49 lands.
   Retention a week, name per job. The trace already survives failure, so this is workflow-only.
2. **Stop the check jobs from needing github.com.** Either
   - split the shipped artifacts (whisper-cpp, ffmpeg) out of what `devenv shell` realizes for the
     lint and test jobs, so those jobs never fetch a release asset, or
   - make `nix-toolchain` save the Nix store cache on failure as well (`save` runs at post even when
     the job fails), so a cold shell is built once and reused.
   The first is the real fix; the second is cheap and bounds the blast radius.
3. **Retire the remaining timing assumptions where a signal exists**: the `13-second-quit` guard
   (capture `app.process()` before the quit, or `app.process?.()`), and `ProcessRunnerSpec`'s
   wall-clock assertions where virtual time can express the same claim. `59-row-bars`'s observation
   follows the backlog row (`docs/known-issues-e2e-flakes`).

Rejected alternatives:

- **Uploading the whole Gradle report tree**: the failure message already reaches the console
  (item 77); what is missing for e2e is the trace, which lives outside it.
- **Retrying specs until green in CI**: hides the flake and keeps the diagnosis impossible.

## Open questions

- Should the upload run for `Linux (e2e)` only on failure, or also on success when a retry passed?
  (Today no spec retries.)
- Is the shell split worth it, or is saving the cache on failure enough until the shipped artifacts
  move behind a separate flake output?

## Test list (proposed; to be approved at the consultation)

- [ ] A deliberately failing e2e run attaches the failing spec's trace, screenshot and
      `error-context.md` to the workflow run
- [ ] A deliberately failing desktop packaged run attaches its failure artifacts too
- [ ] The lint/test jobs' shell closure does not contain the shipped whisper-cpp/ffmpeg artifacts
      (or: a cold shell whose asset host is unreachable still lets actionlint pass)
- [ ] The `13-second-quit` spec passes when the app exits before its `finally` runs
- [ ] (must not break) A green run uploads nothing and keeps its durations
- [ ] (must not break) The artifact-producing jobs still publish their DMG/tar.gz artifacts

## Acceptance criteria

- Every browser or desktop failure lands with its trace in the run, so a diagnosis needs no console
  paste from the owner.
- An unreachable github.com fails only the jobs that fetch from it; a Markdown-only PR cannot fail
  on it.
- The recorded timing assumptions that have a signal-based replacement are gone.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, after the CI triage of runs
  #212, #216, #223, #236, #239 and #246. Not yet discussed.
