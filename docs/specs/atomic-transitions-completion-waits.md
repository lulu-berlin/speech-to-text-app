# 58 — The atomic-transitions spec's completion waits still have real-time windows

Status: done (roadmap item 58, approved 2026-10-18)
Depends on: — (items [47](atomic-transitions-spec-flaky.md) and
[55](atomic-transitions-lock-probe.md) are done; this is the third schedule-dependent
spot in the same spec, at the bounded waits they kept)
Blocks: — (a rare flake, not a solid blocker; it can redden any Nix build that runs the backend's
`gradle check`, including CI's `desktop-nix-e2e` gate)

## Goal

`ProcessingEngineAtomicTransitionsSpec`'s "the next task runs to completion" claims stop failing by
wall-clock luck. Items 47 and 55 made the assertions before them deterministic (the exact event-count
receive, then the writer-lock probe); the remaining failure is the 10-second real-time completion
drain that watches them, and it expired inside a Nix sandbox's `gradle check`. The spec's two
real-time completion waits become signal-based waits on the completed event channel, so a
slow-but-working engine passes however long it takes, and a genuinely stuck engine still fails,
naming the task that never completed.

A fourth manifestation joins this item (the user, 2026-10-18, after PR #20's gate failure): the
writer-lock probe's record-before-enqueue gap, which inverts two racers' commit order when the OS
preempts the first racer's thread between its record and `delegate.lock` (`docs/known-issues.md`,
§ Tests). The probe becomes a test-owned ticket gate that hands the lock out in the launch order.

## Evidence

- Run `38985043592` (PR #9, head `d2facb59`; attempt 1, 2026-10-17), job **Desktop (flake package
  e2e)**, step "Build the flake package and run the packaged desktop e2e against it":
  `nix build .#desktop` failed at `ProcessingEngineAtomicTransitionsSpec.kt:290` with
  `org.opentest4j.AssertionFailedError`, "283 tests completed, 1 failed", in the scenario whose `then`
  is "**the next task, including its DIARIZING phase, runs to completion: the pause cannot hold the
  queue** after the task it was issued for is gone". The same run's attempt 2 (all 11 jobs) passed —
  a scheduling flake, not a logic failure.
- `:290` is the last assertion of that scenario:
  `val published = mutableSetOf<Long>()`, then
  `pumpUntil(10.seconds) { published += drain(fixture.completed); published.containsAll(setOf(first, next)) }`,
  then `published shouldBe setOf(first, next)` (`ProcessingEngineAtomicTransitionsSpec.kt:285-290`).
  `pumpUntil` (`engine/TestEngine.kt:213-224`) is a real-time window: a `System.nanoTime()` deadline,
  alternating `runCurrent()` on the test scheduler with 2 ms real-time delays on `Dispatchers.Default`
  for the file/DB I/O in between. It does not fail when the window expires; it returns, and the
  following `shouldBe` fails without saying which task is missing. Under the loaded Nix sandbox the
  DIARIZING task's terminal publish took longer than 10 s of real time.
- The sibling scenario ("a pause that is accepted while its task's last process is already exiting")
  waits the same way: `pumpUntil(3.seconds) { statusOf(fixture, next) == TaskStatus.COMPLETED }`
  (`ProcessingEngineAtomicTransitionsSpec.kt:335`), followed by `statusOf(fixture, next) shouldBe
  COMPLETED` (`:338`) — the same class, with a shorter window; no failure observed yet.
- The engine's worker runs in the test's `backgroundScope` (`engine/TestEngine.kt:85-100`), so the
  test scheduler drives it; but the chunk/DB work inside is real I/O on `Dispatchers.IO`, which only
  the real-time delays let finish. The subscription forwarding COMPLETED ids to `fixture.completed`
  (`engine/TestEngine.kt:118-135`) is in place before the worker starts, and the first scenario of
  the same spec already waits signal-based — `fixture.completed.receive() shouldBe second` — with no
  window.
- Pre-existing: the spec is byte-identical on `main` and on the item-50 branch that hit the failure
  (verified with `git diff`); #9's merge did not touch it. The `:290` wait is the drain item 47
  introduced and item 55 explicitly kept ("`pumpUntil(10.seconds)` drain stays: it waits on a
  monotone event set (duplicates and order tolerated), which is a legitimate bounded behavioral
  wait"). It is a third manifestation of the spec's schedule-dependence, not introduced by items
  50/57.
- The gate that surfaces it: the spec is untagged, so `gradle check` runs it, and the flake's Nix
  builds run the backend's `gradle check` inside the sandbox (`flake.nix:122`, `doCheck = true` —
  deliberate, item 55 kept it). The same class of failure reddened `main`'s `nix build`
  (#38650675971, `:302`) and PR #9's bundle job (38825990732, `:277`) before; both were fixed at
  their specific lines, these two waits were not.
- Run of PR #20 (item 42, 2026-10-18), job **Nix (flake check)**: `nix build .#desktop`'s
  `gradle check` failed at `ProcessingEngineAtomicTransitionsSpec.kt:101`,
  `resume.await() shouldBe Illegal(CANCELED, QUEUED)`, "294 tests completed, 1 failed", in the
  scenario "a paused task whose cancel races its resume". The resume's write committed first, so it
  was `Accepted`: the probe's order assumption failed, not the engine. The spec is byte-identical to
  `main`; item 42 changed only `TestEngine.kt`'s harness (`storedTaskOf`, `statusesOf`,
  `nextChunkEntered`, the engine-scope parameter), not `CountingMutex`/`queueWrite`
  (`TestEngine.kt:306-309`, `:364-371`) — the documented LOW row in `docs/known-issues.md` § Tests,
  found in item 55's review, firing for the first time.

## Decided (consultation, 2026-10-17)

- **Both waits in this item**: the observed `:286-290` drain and the sibling `:335` wait (the user:
  sweep the class in this spec, not only the observed line).
- **Signal-based, with a long safety bound that names the missing task**: each scenario receives
  from `fixture.completed` until the awaited ids have arrived, under a fixed bound far above any
  load (e.g. 60 s) whose failure names the task that never completed. A slow engine never reaches the
  bound; a stuck one fails with an actionable message. The behavioral assertions after the wait stay
  exactly as they are (item 47's decided form: duplicates and order tolerated).
- **Red first, deterministically**: the red is not the rare CI occurrence but a controlled slow
  engine — the next task's fake diarizer (or transcriber) held past the old window, then released —
  which the current windows fail and the signal-based waits pass.
- **No e2e test** (ADR 0046): a test-harness-only change; no app feature or behavior changes.
- **The probe's gap is closed here** (the user, 2026-10-18): `CountingMutex` takes a ticket
  atomically with the "this writer called lock" record and serves waiters in ticket order (a FIFO
  ticket lock over the delegate mutex), so a racer recorded first is served first however long its
  thread is preempted afterwards; unlabelled writers (the engine's own) take tickets too, so
  exclusion is unchanged. `queueWrite` keeps its shape and its 10 s safety bound (the bound names the
  missing writer). The backlog row leaves `docs/known-issues.md` when this lands, and the pointer
  sentence names this item.

## Test list (approved 2026-10-17; behaviors only)

- [x] The "next task, including its DIARIZING phase, runs to completion" scenario passes when the
      next task's work is held longer than the old 10 s window and then released: the wait follows
      the completed events, not a real-time budget
- [x] The "pause accepted while its task's last process is already exiting" scenario passes when the
      next task's work is held longer than the old 3 s window and then released
- [x] A task that never completes fails either scenario's wait within the safety bound, and the
      failure names the task that is missing
- [x] The scenarios' behavioral claims stay asserted unchanged and green: the first task ends
      COMPLETED, no dead started-PAUSED row is left, the next task diarizes and completes,
      `passedGate` is true, and each awaited id appears at least once (duplicates and order
      tolerated)
- [x] (must not break) All atomic-transition scenarios and the rest of `gradle check` stay green,
      including 20 consecutive runs of the full suite under load
- [x] (must not break, user-verified) `nix build .` and `nix build .#desktop` (with their `doCheck`
      `gradle check`) stay green
- [x] (added and approved 2026-10-18) The probe's commit order follows the launch order, not FIFO
      luck: with the first racer held between its record and its wait (a test-only hold), it is still
      served first, so the resume/cancel scenario keeps its assertions; `CountingMutexSpec` pins the
      ordering, and all five race scenarios pass unchanged

## Acceptance criteria

- No completion wait in the spec can expire on wall-clock luck: its outcome depends on the engine's
  events, not on how fast the machine is; a stuck engine fails naming the missing task.
- The behavioral pins are unchanged; the suite and the Nix-built `gradle check` are green under load.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-17: filed from run `38985043592` attempt 1 (the latest PR #9 build), after the review
  confirmed the spec is byte-identical to `main`. Root cause read from the failing line and the
  helper it calls: the item-47 drain's 10 s real-time window expired under the Nix sandbox's loaded
  `gradle check`; the engine was slow, not stuck.
- 2026-10-17: consultation — the user chose to sweep the sibling `:335` wait in this item and a long
  fixed safety bound that names the missing task; red first via a held fake past the old windows;
  e2e-exempt (test-harness only). Test list proposed as worded above.
- 2026-10-17: spec and test list approved by the user as worded above. Status: spec-approved. Filed
  on `main` through the docs PR that archives item 53.
- 2026-10-17: workspace `workspaces/58-atomic-transitions-spec-completion-window` (branch
  `roadmap/58-atomic-transitions-spec-completion-window`) created from `main` `3f4ad710`.
  Status: in-progress.
- 2026-10-17: workspace consultation — Option A: a permanent real-time hold just past each old window
  (11 s in the DIARIZING scenario, 4 s in the stale-pause scenario) released by a `Dispatchers.Default`
  timer, so the committed tests keep guarding against a return to the old windows; about 15 s per suite
  run accepted. Item 3's test: the real engine with `next`'s diarizer held forever and a 500 ms bound.
  Safety bound 45 s (below `runTest`'s 60 s default, so the failure names the missing task). Helper in
  `TestEngine.kt` next to `drain`/`pumpUntil`, with its own `AwaitCompletedSpec`. First cycle: test-list
  item 1, the observed CI failure. Virtual time cannot produce the red: `runTest` advances it while the
  test body waits on another dispatcher.
- 2026-10-17 (cycle 1, test-list item 1, the DIARIZING completion wait): red — the scenario's next task
  is held in `FakeDiarizer` and released 11 s after the wait starts, past the old 10 s window; with the old
  `pumpUntil(10.seconds)` drain the spec failed `org.opentest4j.AssertionFailedError: expected:<[1L, 2L]>
  but was:<[1L]>` at `ProcessingEngineAtomicTransitionsSpec.kt:301` (6 tests, 1 failed), the CI failure's
  exact shape. Green — `awaitCompleted(completed, ids, bound = 45 s)` in `TestEngine.kt` receives from
  `fixture.completed` on `Dispatchers.Default` until every awaited id arrived (duplicates and order
  tolerated) and throws an `AssertionError` naming the missing ids if the bound passes; scenario 5 uses it,
  and the behavioral assertions after it are unchanged. Spec green: 6 tests, 0 failures, 26 s;
  `ktlintCheck` clean.
- 2026-10-17 (cycle 2, test-list item 2, the stale-pause completion wait): red — the same hold on the
  next task's DIARIZING phase, released 4 s after the wait starts, past the old 3 s window; with
  `pumpUntil(3.seconds)` the spec failed `org.opentest4j.AssertionFailedError: expected:<true> but
  was:<false>` at `ProcessingEngineAtomicTransitionsSpec.kt:352` (`passedGate`), 6 tests, 1 failed.
  Green — the wait is `awaitCompleted(fixture.completed, setOf(next))`; the three behavioral assertions
  after it are unchanged. Spec green: 6 tests, 0 failures, 22 s. Items 1 and 2 ticked.
- 2026-10-18 (cycle 3, test-list item 3, the safety bound): the helper's failure path was new in cycle 1,
  so the new spec is green against it by construction. Controlled red (documented, test-harness only):
  `awaitCompleted`'s throw replaced by the pre-cycle-1 silent `return received`; `AwaitCompletedSpec`
  (task 2 held in DIARIZING forever, bound 500 ms) failed `org.opentest4j.AssertionFailedError: Expected
  exception java.lang.AssertionError but no exception was thrown.` at `AwaitCompletedSpec.kt:65`, 1 test,
  1 failed. Green with the throw restored: the message names `task(s) [2]` and
  `completed ids received: [1]`; 1 test, 0 failures, 2.8 s.
- 2026-10-18 (test-list item 4, behavioral pins unchanged): `git diff main -- ProcessingEngineAtomic-
  TransitionsSpec.kt` shows only the two holds and the two wait calls; every assertion-bearing line
  (48 `shouldBe`/`shouldBeTrue`/`startedPaused`/`passedGate` lines) is byte-identical to `main`
  (`diff` over the filtered lines: identical). Spec green: 6 tests, 0 failures, 17.7 s (`--rerun`).

- 2026-10-18: extended from PR #20's "Nix (flake check)" failure at `ProcessingEngineAtomicTransitionsSpec.kt:101`
  (the documented writer-lock probe gap firing), at the user's direction: the probe becomes a
  test-owned ticket gate in this item (the user's choice over a new item), the Goal/Evidence/Decided
  sections name it, and the test list gains one approved item. The `docs/known-issues.md` § Tests row
  leaves the backlog in the squash, and the pointer sentence names item 58.

- 2026-10-18 (test-list item 7, the added writer-lock probe ordering): red — the new `CountingMutexSpec`
  scenario holds the first racer between its record and its wait (`holdBeforeWait` seam), lets a second
  racer reach the lock, then releases both; the current probe served the second first:
  `org.opentest4j.AssertionFailedError: Element differ at index: [0, 1] expected:<["first", "second"]>
  but was:<["second", "first"]>` at `CountingMutexSpec.kt:76` (2 tests, 1 failed). Green —
  `CountingMutex` takes a ticket in the same critical section as its `writers` record and serves tickets
  FIFO through a turn deferred; a cancelled waiter drops its ticket or passes on a granted turn, so the
  10 s probe bound still fails naming the missing writer. `awaitLockCall`'s gap caveat removed. Full
  `gradle check`: 309 tests, 0 failures, 112 suites, `ktlintCheck` clean, 1m05s; focused
  `CountingMutexSpec` 2/2 and `ProcessingEngineAtomicTransitionsSpec` 6/6 (`--rerun`, 22 s); the spec's
  assertion-bearing lines are unchanged (`git diff main` filtered: none differ). Refactor (approved):
  `releaseTurn()` extracted from `unlock` and the cancellation path (commit `187353d7`); focused specs
  green after it (`CountingMutexSpec` 2/2, `ProcessingEngineAtomicTransitionsSpec` 6/6, `--rerun`, 53 s).

- 2026-10-18 (test-list item 5, 20 runs under load): the user ran 20 consecutive
  `gradle check --rerun-tasks` from `workspaces/58-atomic-transitions-spec-completion-window/backend`
  at `815f0fb3` under `CONVEX_LOAD=1` with `nproc` CPU hogs — all 20 exit 0, **320 tests / 0 failures**
  each (114 suites, `ktlintCheck` clean), 204–278 s per run, 1h18m25s total. Summary:
  `/tmp/item58-check/summary.txt`. Item 5 ticked.
- 2026-10-18 (test-list item 6, Nix builds): blocked in the agent sandbox — `/nix/store` is read-only,
  and even `nix store add-file` on a probe file fails (`Read-only file system`; entering the worktree
  fails the same way while devenv instantiates its shell). `nix build .` and `nix build .#desktop`
  must run in a writable-store environment (host or CI). Item 6 stays open until then.

- 2026-10-18 (test-list item 6, the Nix builds): green on the user's laptop at `65d67ebf` — after
  pulling the branch to a writable-store environment, `nix build -L . --no-link` exited 0 with the
  sandboxed `doCheck` `gradle check` (`ktlintCheck` + the full suite) `BUILD SUCCESSFUL in 2m 10s`,
  and `nix build -L .#desktop --no-link` exited 0. All test-list items are now green; Status:
  in-review.

- 2026-10-18: main-tree review approved by the user; the log's refactor hash corrected from the
  pre-rewrite `7a48c361` to the branch's `187353d7` (root agent, in the squash); spec moved to
  `docs/specs/atomic-transitions-completion-waits.md` with `Status: done`, squashed to one commit on
  top of `main` (ADR 0050).
