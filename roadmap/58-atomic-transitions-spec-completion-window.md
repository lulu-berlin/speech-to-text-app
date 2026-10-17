# 58 — The atomic-transitions spec's completion waits still have real-time windows

Status: spec-approved
Depends on: — (items [47](../docs/specs/atomic-transitions-spec-flaky.md) and
[55](../docs/specs/atomic-transitions-lock-probe.md) are done; this is the third schedule-dependent
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

## Test list (approved 2026-10-17; behaviors only)

- [ ] The "next task, including its DIARIZING phase, runs to completion" scenario passes when the
      next task's work is held longer than the old 10 s window and then released: the wait follows
      the completed events, not a real-time budget
- [ ] The "pause accepted while its task's last process is already exiting" scenario passes when the
      next task's work is held longer than the old 3 s window and then released
- [ ] A task that never completes fails either scenario's wait within the safety bound, and the
      failure names the task that is missing
- [ ] The scenarios' behavioral claims stay asserted unchanged and green: the first task ends
      COMPLETED, no dead started-PAUSED row is left, the next task diarizes and completes,
      `passedGate` is true, and each awaited id appears at least once (duplicates and order
      tolerated)
- [ ] (must not break) All atomic-transition scenarios and the rest of `gradle check` stay green,
      including 20 consecutive runs of the full suite under load
- [ ] (must not break, user-verified) `nix build .` and `nix build .#desktop` (with their `doCheck`
      `gradle check`) stay green

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
