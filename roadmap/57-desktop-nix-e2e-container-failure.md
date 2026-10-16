# 57 — Diagnose and fix the desktop-nix-e2e container exit 6

Status: spec-approved
Depends on: — (the affected harness and workflow are in item 50's open PR; this is a TDD follow-up
in the item-50 worktree, as the user decided)
Blocks: [50](50-electron-app.md) (PR #9's `desktop-nix-e2e` gate)

## Goal

The `desktop-nix-e2e` GitHub Actions job identifies and fixes its container-run failure instead of
ending with only `[ELIFECYCLE] Command failed with exit code 6`. A fresh Ubuntu 24.04 runner must build
`.#desktop`, run the packaged desktop specs in the container, and complete the job successfully. The
current cause is unknown; do not treat disk exhaustion as established without evidence.

## Evidence

- PR #9, run `38825990732`, job **Desktop (flake package e2e)**, failed. The Nix build completed:
  the log shows `stt-desktop-0.1.0` produced at `00:17:32`, after the `whisper-cpp` source build
  completed. `run-in-docker` then announced the Ubuntu 24.04 test container, Docker pulled the image,
  and pnpm returned `ELIFECYCLE` exit 6 at `00:17:35`; the supplied log has no container stderr or
  failing command after the image pull.
- The job is part of CI's required `ci` aggregator. It is separate from the bundle job's failure in the
  same run: item 55 tracks the `ProcessingEngineAtomicTransitionsSpec.kt:277`
  `TimeoutCancellationException` in the bundle build's `gradle check`.
- `e2e/desktop/run-in-docker.ts` invokes Docker with `stdio: 'inherit'` and exits with the Docker
  process's status (`:413-415`). The actual cause of exit 6 has not been diagnosed.

## Decisions (consultation, 2026-10-16)

- **Own roadmap item, diagnose first.** The cache item is not the fix for this job: its Nix build
  succeeded, and the failure happened after Docker started. Measure available disk and capture the
  exact child/container failure before choosing a change.
- **Workspace**: develop this as a separate TDD follow-up in the existing
  `workspaces/50-electron-app/` branch so the fix can update PR #9. The spec and test list are tracked
  as item 57; implementation commits stay on item 50's branch and are included in its eventual single
  squash. This is a user-approved exception to the usual one-worktree-per-item workflow.
- **No presumed disk fix**: add runner cleanup only if evidence shows disk headroom caused the failure.

## Proposal

Re-run the exact failing `desktop-nix-e2e` path in a CI-like Ubuntu 24.04 environment while collecting
the Docker invocation, container output, exit status and available disk space. Use the observed cause
to make the smallest fix. Keep failures actionable: if the container command fails, the CI output must
show the failed step and its error, not only the outer pnpm lifecycle status.

## Test list (approved 2026-10-16; diagnose-first behaviors)

- [ ] Reproducing the CI-equivalent cold-store run identifies and records the actual failing container
      command, its stderr and exit status, and disk headroom; the root cause is evidence-based, not
      assumed from the exit code.
- [ ] After the cause is fixed, a fresh Ubuntu 24.04 `desktop-nix-e2e` run builds `.#desktop`, runs
      the applicable packaged specs in the container and exits successfully; a container failure, if
      one occurs, is reported with its failing step and actual output/status.
- [ ] (must not break) The `.deb` and bundle packaged specs, GPU modes (on the user's machine) and
      Nix checks stay green.

## Acceptance criteria

- The container exit-6 cause is recorded with the actual command/output and any disk measurements;
  the fix addresses the observed cause.
- `desktop-nix-e2e` is green on a fresh GitHub Actions run; all test-list items are checked and the
  user approved the diff.

## Approval log

- 2026-10-16: drafted after PR #9's `desktop-nix-e2e` failed with exit 6 after the flake package had
  built. The user chose a separate diagnose-first item and a TDD follow-up in item 50's existing
  worktree so the fix can unblock that PR. Test list approved as worded above. Status: spec-approved.
