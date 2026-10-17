# 57 — Unblock the desktop CI: the container exit 6 and the races that fail CI

Status: done (roadmap item 57, approved 2026-10-17)
Depends on: — (the affected harness and workflow are in item 50's open PR; the user approved this
TDD follow-up in the item-50 worktree)
Blocks: [50](electron-desktop-app.md) (PR #9's `desktop-nix-e2e` gate)

## Goal

The `desktop-nix-e2e` GitHub Actions job identifies and fixes its container-run failure instead of
ending with only `[ELIFECYCLE] Command failed with exit code 6`. A fresh Ubuntu 24.04 runner must build
`.#desktop`, run the packaged desktop specs in the container, and complete the job successfully. The
current cause is unknown; do not treat disk exhaustion as established without evidence.

**Scope expanded (the user, 2026-10-16):** the item also removes the test races that can fail a CI
run, so PR #9's gates stop flaking: the scratch-file probe that throws while the engine deletes files
(it failed a `desktop-nix-e2e` re-run inside the Nix build's `gradle check`), the paused-runner
check's fixed window, and the e2e timing windows in `15-screenshots`, `06-playback` and
`17-follow-mode`. Out of scope: the atomic-transitions writer-lock wait (item 55, on its own branch)
and the fixed "nothing happens" windows that can only pass vacuously, never fail (they stay in
`docs/known-issues.md`).

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
  process's status (`:413-415`). The actual cause of exit 6 is diagnosed and reproduced under
  [Cycle log](#cycle-log).
- The job's re-run on PR #9's merge ref `76e70fd2` (head `a1d5216f` merged into `5f7223ef`),
  2026-10-16 03:04 UTC, failed before Docker: `nix build .#desktop` builds the backend with its
  `gradle check` (`doCheck = true`), and 1 of 280 tests failed — `ProcessingEngineCrashScratchSpec`,
  `UncheckedIOException` caused by `NoSuchFileException` at `:67`. That line polls
  `filesUnder(scratch.dir)`, a bare `Files.walk` (`engine/TestEngine.kt:250-255`), while the
  engine's startup clear deletes the same tree; a directory removed mid-walk throws. The harness
  named the step: `run-in-docker: nix build .#desktop failed (exit 1)`. The same helper is polled
  while files are deleted by `http/UpgradeCrashScratchIntegrationSpec.kt:55,68`, and
  `engine/ProcessingEngineRestartIntegrationSpec.kt:121` holds a copy of it.
- Read 2026-10-16, able to fail a run under load: `process/ProcessRunnerSpec.kt:553-563`
  (`pauseAndExpectFrozen` allows at most one more line within 500 ms of a pause; lines a slow reader
  has not drained yet can be more than one); `e2e/tests/15-screenshots.spec.ts:42-68` (eight shots
  inside the ~77 s seed's live window, before the test cancels the seed). From the backlog,
  unverified: `e2e/tests/06-playback.spec.ts:87` (10 s playback-advance poll) and
  `e2e/tests/17-follow-mode.spec.ts:143` (5 s follow-scroll poll).

## Decisions (consultation, 2026-10-16)

- **Own roadmap item, diagnose first.** The cache item is not the fix for this job: its Nix build
  succeeded, and the failure happened after Docker started. Measure available disk and capture the
  exact child/container failure before choosing a change.
- **Workspace**: develop this as a separate TDD follow-up in the existing
  `workspaces/50-electron-app/` branch so the fix can update PR #9. The spec and test list are tracked
  as item 57; implementation commits stay on item 50's branch and are included in its eventual single
  squash. This is a user-approved exception to the usual one-worktree-per-item workflow.
- **No presumed disk fix**: add runner cleanup only if evidence shows disk headroom caused the failure.

## Decisions (scope expansion, 2026-10-16)

- **Expanded in item 57**, not a new item (the user's choice): the races are fixed in the same
  item-50 worktree and squash.
- **Only races that can fail a CI run.** Fixed "nothing happens" windows that a slow machine can
  only pass vacuously (`ProcessingEngineQueuePauseSpec.kt:35`, `ProcessingEngineCancelSpec.kt:78,112`,
  `SpeakerIdsSpec.kt:224`, `EmbeddingsSpec.kt:97`, `EventsDisconnectSpec.kt:47`,
  `ProcessRunnerSpec.kt:376`) stay in the backlog.
- **Item 55 is not duplicated**: its writer-lock fix reaches item 50 when 55 is merged and the
  root agent synchronizes this branch.
- **e2e polls verified first**: `06-playback` and `17-follow-mode` are changed only if a wait is
  confirmed able to fail under load; otherwise that list item is dropped and the issue returns to
  the backlog.

## Proposal

Re-run the exact failing `desktop-nix-e2e` path in a CI-like Ubuntu 24.04 environment while collecting
the Docker invocation, container output, exit status and available disk space. Use the observed cause
to make the smallest fix. Keep failures actionable: if the container command fails, the CI output must
show the failed step and its error, not only the outer pnpm lifecycle status.

## Test list (approved 2026-10-16; diagnose-first behaviors; expanded and approved 2026-10-16)

- [x] Reproducing the CI-equivalent cold-store run identifies and records the actual failing container
      command, its stderr and exit status, and disk headroom; the root cause is evidence-based, not
      assumed from the exit code.
- [x] After the cause is fixed, a fresh Ubuntu 24.04 `desktop-nix-e2e` run builds `.#desktop`, runs
      the applicable packaged specs in the container and exits successfully; a container failure, if
      one occurs, is reported with its failing step and actual output/status.
- [x] Listing the files under a directory returns the files that remain, without an error, while
      another writer deletes files and subdirectories in it; the engine and upgrade crash-scratch
      specs and the restart integration spec use that one helper.
- [x] A paused run delivers no new output: lines already read before the pause may still be
      delivered, and once they are drained no further line arrives until the run is resumed,
      however slowly the reader drains.
- [x] (e2e) `15-screenshots` cancels the seed while it is still running, however long its eight
      screenshots take.
- (dropped 2026-10-16, returned to the backlog) `06-playback`'s playback-advance wait and
  `17-follow-mode`'s follow-scroll wait could not be shown to fail under load: both wait on the right
  signal with a 10 s / 5 s bound, and both passed 3 of 3 loaded runs (see Cycle log).
- [x] (discovered, confirmed 2026-10-16) A descendant that left the run's process group survives a
      cancel of the run however soon the cancel comes: the escape is established before the scenario
      cancels (`process/ProcessRunnerSpec.kt:431-445` failed under load,
      `expected:<[165426L]> but was:<[]>` at `:442`).
- [x] (discovered, confirmed 2026-10-16) `03-upload-queue`'s live-state checks hold however fast the
      first clip runs: each is a claim about what the rows showed, recorded in the page from before
      the uploads, not a moment the test has to catch (CI run `38848511391`, job `E2E (Playwright)`:
      the diarization stage label of `jfk-a.wav`, diarized in 0.3 s, was never seen at `:76`).
- [x] (discovered, confirmed 2026-10-16) The bundle's packaged specs launch in the test container
      on an AppArmor host such as the CI runner, not only on a host without it (CI run
      `38907181487`, job `Desktop (bundle e2e)`: every launch failed with `bwrap: Failed to make /
      slave: Permission denied`, 11 failed, 3 skipped).
- [x] (must not break) The `.deb` and bundle packaged specs, GPU modes (on the user's machine) and
      Nix checks stay green; `gradle check`, `gradle integrationTest`, the e2e suite and `pnpm test`
      stay green, including repeated runs under load and `nix build .` on the user's machine.

## Cycle log

- **Item 1 — reproduce the CI-equivalent run and identify the cause (2026-10-16).** Run on the
  user's Docker host with the exact `--nix` harness argv (`run-in-docker.ts` `:375-411`): stock
  `ubuntu:24.04`, `/nix/store` read-only mount, `e2e/` at `/e2e`, `HOST_UID=1001`,
  `HOST_GID=1001` (the GitHub runner's uid), `nix build .#desktop` on the host.
  - **Red** (the pre-fix container script, as at head `81b49b09`): the container printed only a
    diagnostic `df -h /` line and exited **6** with no error output. The exact failing command is
    `userdel -r ''`: the guard `if existing=$(getent passwd "$HOST_UID" | cut -d: -f1)` is true
    with an empty name because the substitution's status is its pipeline's last command (`cut`,
    always 0); `userdel -r ''` exits 6 with its stderr suppressed (the pre-fix script's
    `2>/dev/null`), so `set -eu` stopped the container before anything printed. Docker exited 6
    and `run-in-docker.ts:413-415` propagated it; pnpm's outer `ELIFECYCLE … exit code 6` is that
    status — the observed `desktop-nix-e2e` failure.
  - **Green** (the current harness, fix `189ec91f`): the uid-1001 test user is created by name and
    the packaged specs ran: **11 passed, 3 skipped**, `exit=0`, matching the 2026-10-15 uid-1000
    baseline (`docs/specs/electron-desktop-app.md`). The forced-uid local run needed `chmod a+w
    e2e` because the host's `e2e/` tree is uid-1000-owned; CI's checkout is uid-1001-owned, so
    nothing of the kind exists there.
  - **Disk headroom**: `df -h /` showed `447G` available of `938G` (50% used) at run time; the
    disk-exhaustion hypothesis is refuted — no ENOSPC or I/O error in either run.
  - **Root cause, evidence-based**: the runner's uid 1001 is never present in `ubuntu:24.04`
    (whose own user is uid 1000) and tripped the pre-fix guard. Fixed in `189ec91f` (decide the
    image user's removal by name, keep its stderr), already in this branch and in PR #9 head
    `a1d5216f`.
- **Item 2 — green on GitHub Actions (2026-10-16, user-verified).** The earlier green run on
  `a1d5216f` was no longer accessible (the job was re-run over it). Run `38907181487` (CI #45, PR #9
  head `ed97c90`, rebased onto `main` `5a0d3de7`, all of this item's fixes in): **Desktop (flake
  package e2e)** succeeded in 20:08 — a fresh Ubuntu 24.04 runner built `.#desktop` and ran the
  packaged specs in the container. In the same run `Backend (gradle check)`, `Backend (gradle
  integrationTest)`, `E2E (Playwright)`, `Desktop (packaged .deb e2e)`, `Frontend (pnpm test, pnpm
  build)`, `Nix (build)`, `Nix (flake check)` and `Nix (devenv shell)` succeeded. Failure reporting
  was shown by the earlier re-run (`run-in-docker: nix build .#desktop failed (exit 1)` with the Nix
  log) and by the forced-uid run's `EACCES` lines (item 1).
- **Item 3 — listing a tree while it is deleted (2026-10-16).** New `engine/FilesUnderSpec`: 20 rounds
  of 300 task directories, each `chunking/canonical.flac`, deleted by a coroutine on
  `Dispatchers.IO` while the spec calls `filesUnder` on the root until the deletion ends; every
  listing must succeed, return only that tree's files, and the last listing must be empty.
  - **Red** (the bare `Files.walk`, `engine/TestEngine.kt:250-255`): failed in 4 of 4 runs, 59–65
    failed listings per run, each `java.io.UncheckedIOException: java.nio.file.NoSuchFileException:
    /tmp/stt-files-under…/task-255` (or `…/task-277/chunking/…`) — the CI failure's exception.
  - **Green**: `filesUnder` walks with `Files.walkFileTree` and a visitor that treats
    `NoSuchFileException` (an entry or directory deleted mid-walk) as gone and rethrows anything
    else. `FilesUnderSpec` green in 4 of 4 runs; `gradle check` green (304 unit tests, 0 failures,
    ktlint clean); the Integration specs on the same helper, `UpgradeCrashScratchIntegrationSpec`
    and `ProcessingEngineWhisperInputIntegrationSpec`, green (1 and 3 tests).
  - **Refactors** (user-approved): `ProcessingEngineRestartIntegrationSpec`'s private `scratchFiles`
    replaced by the shared `filesUnder` (`625881f3`; that spec and `gradle check` green), and
    `FilesUnderSpec`'s tree-file filter and clip name named (`cfd6da64`).
- **Item 4 — a paused run with a slow reader (2026-10-16).** New `ProcessRunnerSpec` scenario (an
  Integration spec, so CI's `backend-integration` job): the counting loop writes a line every 50 ms,
  the reader takes 150 ms per line, and the runner is paused once line 3 is delivered.
  - **Red** (the old `pauseAndExpectFrozen`: at most one more line within 0.5 s of the pause): failed
    in 3 of 3 runs, `3 should be <= 1` — the reader drained three lines written before the pause.
  - **Green**: `pauseAndExpectFrozen` waits (up to 10 s) until every process of the run's tree — the
    test JVM's live descendants — is stopped (`T` in `/proc/<pid>/stat`; a zombie child counts as
    not writing), so no line can be added however slowly the reader drains; the new scenario also
    waits for the already-written lines to arrive and checks they are 1, 2, 3, … without a gap. The
    two other scenarios that used the check (pause/resume, second run on the same runner) use the
    new one. `ProcessRunnerSpec` green in 3 of 3 runs (24 tests); under load (a busy loop on each of
    8 cores) every pause scenario passed, and an unrelated scenario failed — the discovered item.
  - **Refactor** (user-approved): one `pollUntil` helper serves the frozen-tree wait and the drain
    wait; `ProcessRunnerSpec` green (24 tests), ktlint clean.
- **Discovered item — the escaped descendant (2026-10-16).** `ProcessRunnerSpec.kt:429-450`'s shell
  printed `$$ $!` as soon as it had forked `setsid sleep 1000`, so a cancel landing before the child's
  `setsid()` killed it with the group.
  - **Red**: with a 0.3 s delay before the child's `setsid` (`(sleep 0.3; exec setsid sleep 1000) &`,
    the loaded machine's timing made the rule), failed in 3 of 3 runs, `expected:<[pid]> but was:<[]>`
    at `:442` — the failure the loaded run showed.
  - **Green**: the shell reports the pids only once the child's process group (field 5 of
    `/proc/$!/stat`) is no longer the shell's. `ProcessRunnerSpec` green in 3 of 3 runs and in 2 of 2
    under load (a busy loop on each of 8 cores), 24 tests each.
  - **Refactor checkpoint**: none suggested (a one-command change in one scenario).
- **Item 5 — `15-screenshots` holds its seed (2026-10-16).** The seed only has to *transcribe* its
  ~77 s clip, which the tiny model can do in seconds, so a slow shot loop (12 live shots) could let it
  finish before the test clicks Cancel. User's choice of fix: pause the queue once the seed shows
  "Transcribing" (its whisper process freezes, ADR 0004), so the live shots now show the "Pause the
  queue" switch on; resume after the cancel so the queued upload transcribes.
  - **Red** (the spec unchanged, plus a temporary 90 s wait standing in for slow shots, never
    committed): `locator.click: Test timeout of 360000ms exceeded`, waiting for `Cancel jfk-k.wav`
    at `:65` — the seed had finished on its own.
  - **Green**: with the queue pause and the same temporary 90 s wait, `1 passed (2.0m)`; with the
    wait removed, `1 passed (28.8s)`. Prettier clean. Run in the sandbox against the real backend;
    `pnpm --dir frontend build` cannot run here (its `node_modules/.bin` scripts need `/bin/sh`),
    and the existing `frontend/dist` was built at the last frontend commit, so it is current.
- **Item 6 — `06-playback` and `17-follow-mode` verified, dropped (2026-10-16).** Read first: the
  item's two waits are `06-playback.spec.ts:87` (up to 10 s for the time display to move after Play)
  and `17-follow-mode.spec.ts:122` (up to 5 s for the highlighted segment to scroll into view; the
  backlog's `:143` was stale — that line is a 500 ms sleep before a hidden check). Both specs run
  together 3 times with a busy loop on each of 8 cores, on the rebased tree: `2 passed` each time
  (6.6, 6.8 and 6.7 min). Per the approved rule the item is dropped and both waits return to
  `docs/known-issues.md` with that evidence.
- **Discovered item — `03-upload-queue` records what the rows showed (2026-10-16).** CI run
  `38848511391` (PR #9 merge `8bdc75dd`, job `E2E (Playwright)`, 1 of 21 failed): `:76` waited 60 s
  for `jfk-a.wav`'s "Embedding/Clustering N%" label; the clip diarized in 0.3 s (task 1, speaker
  model loaded `04:06:51.162`, summary `04:06:51.481`) and its row left the pending list before the
  check looked. The checks at `:52` (list order), `:59-63` (RUNNING, phase, bar, percentage), `:60`
  (b QUEUED) and `:83-88` (finish notices) had the same shape; item 47 had already made `:64-70` (the
  percentages) a recorded claim. On `main` since 2026-10-11, not item 50's code.
  - **Red** (the spec unchanged, plus a temporary wait for `jfk-a.wav transcribed` before the live
    checks, standing in for a fast engine; never committed): `element(s) not found` for `RUNNING` on
    `jfk-a.wav`, the first live check.
  - **Green**: one in-page recorder (a `MutationObserver` installed before the uploads) keeps every
    distinct state of the three rows — list order, text, progress-bar names — plus `jfk-a`'s
    transcription percentages and the order the finish notices appeared; the test waits for all
    three notices in order, then asserts on the record: the rows pending in upload order, `jfk-a`
    RUNNING while `jfk-b` QUEUED, `jfk-a` RUNNING with a phase label, its transcription bar and a
    percentage, more than one percentage, and its diarization bar with an Embedding/Clustering
    label. Green with the same temporary wait (`1 passed (25.4s)`); without it, the full e2e suite
    `21 passed (7.1m)`. Prettier clean.
  - **Refactor checkpoint**: none suggested (the recorder has a single user).

- **Must-not-break, GPU modes (2026-10-16, user-run on the user's machine at `8318b16c`).**
  `pnpm --dir e2e test:desktop:bundle:amd`: **12 passed, 2 skipped (4.0m)**, spec 10 (the AMD GPU)
  green. `pnpm --dir e2e test:desktop:bundle:nvidia`: **12 passed, 2 skipped (4.8m)**, spec 11 (the
  NVIDIA GPU) green.
- **Must-not-break, CI run `38912611745` (PR #9 head `03075e0d`, 2026-10-16, all green).**
  `Desktop (bundle e2e)` (the AppArmor cycle above), `Desktop (flake package e2e)`, `Desktop
  (packaged .deb e2e)`, `Backend (gradle check)`, `Backend (gradle integrationTest)`, `E2E
  (Playwright)`, `Frontend (pnpm test, pnpm build)`, `Nix (build)`, `Nix (flake check)`, `Nix
  (devenv shell)` and the `CI (all checks)` aggregator all succeeded; `Pinned model hosts resolve`
  skipped as designed.
- **Discovered item — the bundle harness on an AppArmor host (2026-10-16).** `Desktop (bundle e2e)`
  had never passed in CI (runs `38825990732`, `38837795299`, `38848511391` failed earlier in the
  step); run `38907181487` is the first to reach the container, and every spec's launch failed
  within 4 s: `bwrap: Failed to make / slave: Permission denied`. bubblewrap's first step is
  `mount(NULL, "/", MS_SLAVE|MS_REC)`; `EACCES` (not `EPERM`) is an LSM refusal, and Docker
  confines every container on an AppArmor host with `docker-default`, whose template has
  `deny mount,` (moby/profiles `apparmor/template.go:74`). The user's host passes the same specs,
  so its Docker applies no such profile; the runner's step that sets
  `kernel.apparmor_restrict_unprivileged_userns=0` concerns a different AppArmor restriction.
  - **Red**: CI run `38907181487` above (the sandbox cannot run Docker).
  - **Fix**: `bubblewrapInDocker` (`e2e/desktop/run-in-docker.ts:98-105`) adds
    `--security-opt apparmor=unconfined`, beside the seccomp and `/proc` relaxations; the app's own
    sandbox is unchanged. `tsc` and prettier clean.
  - **Green** (CI run `38912611745`, PR #9 head `03075e0d`, 2026-10-16; step log supplied by the
    user): `Desktop (bundle e2e)` succeeded (job 41:33; its step `Build the bundle and run the
    packaged desktop e2e against it` 38:32). `tsc -p desktop/tsconfig.json && node
    desktop/run-in-docker.ts --bundle` ran the specs in alpine:3.20 against
    `/nix/store/r1x4hgvcjk4mmxqg2c6q8kjy3ilna7lq-stt-app-bundle`: **11 passed (4.1m), 3 skipped**
    (specs 05, 10, 11 — the Nix-store cache and both GPU specs), no `bwrap` launch failure,
    matching the 2026-10-15 uid-1000 baseline. The ~27.5 min `stt-desktop-app-0.1.0` build (the
    Vulkan-only Mesa compile) is item 56's territory.

## Acceptance criteria

- The container exit-6 cause is recorded with the actual command/output and any disk measurements;
  the fix addresses the observed cause.
- `desktop-nix-e2e` is green on a fresh GitHub Actions run; all test-list items are checked and the
  user approved the diff.
- The races in the test list are fixed with signal-based waits or race-tolerant probes, each shown
  red first; the CI gates no longer fail on them.

## Approval log

- 2026-10-16: drafted after PR #9's `desktop-nix-e2e` failed with exit 6 after the flake package had
  built. The user chose a separate diagnose-first item and a TDD follow-up in item 50's existing
  worktree so the fix can unblock that PR. Test list approved as worded above. Status: spec-approved.
- 2026-10-16: spec approved on `main` as `roadmap/57-desktop-nix-e2e-container-failure.md`.
  Workspace copy is in-progress; the implementation follows the item's test list as a TDD follow-up
  in the item-50 workspace, per the user's decision. First test and cause remain pending.
- 2026-10-16: scope expanded by the user to the races that can fail a CI run (test-list items 3–6
  added, the must-not-break item extended), tracked in this item rather than a new one; the expanded
  test list approved. Item 55's writer-lock wait and the vacuous-pass windows stay out.
- 2026-10-16: test list complete, in review. The bundle AppArmor item and the must-not-break item
  ticked on CI run `38912611745` (all jobs green, PR #9 head `03075e0d`); GPU modes green on the
  user's machine at `8318b16c`. Nothing pending; the root agent's squash sets `done` (AGENTS.md
  workflow step 8).
- 2026-10-17: main-tree review re-ran the backend suites and doc checks on the branch head
  `d2facb59` — all green; the user approved the combined 50+57 diff and the root agent prepared
  PR #9 as a single squashed commit with this spec and item 50's finished state.
