# 94 — The atomic-transitions lock probe must not expire on a starved runner

Status: done (roadmap item 94, approved 2026-11-06)
Depends on: [55](atomic-transitions-lock-probe.md) (done; the writer-keyed probe), [47](atomic-transitions-spec-flaky.md) and [58](atomic-transitions-completion-waits.md) (done; the spec's other deterministic waits), [11](processing-engine.md) (done)
Blocks: —

## Goal

The writer-lock probe in `ProcessingEngineAtomicTransitionsSpec` completes on the writer's own signal,
with no real-time bound tight enough to expire under load, so a starved CI runner cannot red `gradle
check` on it; a write that ends without taking the lock still fails with a message naming the writer.

## Evidence

- **Reported by the user as a MED finding, CI run 42145117147 #569 (2026-11-06), `macOS (gradle
  check)`**: `383 tests completed, 1 failed` — the "the completion still wins … the pause cannot hold
  the queue after the task it was issued for is gone" scenario failed with `writer pause has not
  called lock() within 10s`.
- **The wait is already writer-keyed** (`backend/src/test/kotlin/stt/engine/TestEngine.kt:532-539`):
  `lock.writers.first { writer in it }`, item 55's signal, on an append-only
  `MutableStateFlow<Set<LockWriter>>` (`:429-430`, so a wait cannot miss a membership already
  recorded). What expired is the bound around it: `withContext(Dispatchers.Default) {
  withTimeoutOrNull(PROBE_TIMEOUT_MS.milliseconds) }` with `PROBE_TIMEOUT_MS = 10_000` (`:556`). The
  writer's coroutine is launched on `Dispatchers.Default` (`queueWrite`, `:545-551`) and on the loaded
  runner was starved of CPU; the signal never failed, the clock did.
- **Items 47, 55 and 58 are done** and fixed different waits (the event-count drains, the count-keyed
  probe, the completion windows); this is the probe's own real-time bound, and no item plans it.
- **The suite's precedent for a safety net**: `awaitCompleted(completed, ids, bound = 45 s)` bounds by
  a generous real time and names the missing ids, and `runTest` itself times out at 60 s. A red check
  job fails `CI (all checks)`.
- **The failure-path test uses the same clock**: `CountingMutexSpec.kt:45` asserts
  `awaitLockCall(lock, notYet, timeout = 200.milliseconds)` throws naming the writer, so the new API
  has to give that test a clock-free meaning.

## Decided (consultation, 2026-11-06)

- The user filed this item directly from the finding (no `docs/known-issues.md` row is kept for it).
- The fix must remove the wall-clock success path, not widen it: the probe waits for the writer's
  ticket, and a writer that finishes without taking the lock is the failure the probe names.

## Proposal

1. Make the probe's success path signal-only: wait for the writer's membership in `lock.writers`
   with no timeout (the flow only grows, so membership is never lost).
2. Give the wait a clock-free failure: `queueWrite` races the writer's membership against its own
   `Deferred` completing **without ever taking the lock**, and fails naming the writer in the second
   case (`writer X finished without calling lock()`). A writer that is scheduled late waits; a writer
   that returned early or failed fails the probe at once.
3. Keep the last-resort safety net at the scenario level: `runTest`'s own timeout (generous enough for
   a starved runner), so a genuinely hung test still fails, but the probe itself never names a clock.
4. Adapt `CountingMutexSpec`'s failure-path test to the new meaning (a write that completes without
   locking), and prove the old failure deterministically: hold the writer's dispatcher past the old
   10 s bound in one focused test — red on the current code, green after.

## Open questions

To settle at the item's first checkpoint: whether the deliberate >10 s hold test may live in the suite
(it is a real-time wait of its own, though only as the red), or whether the deterministic red is the
writer's completion-without-lock path plus 20 loaded runs as the determinism bound (item 55's
precedent).

## Test list

- [x] (basic) A writer that takes its ticket later than the old 10 s bound is awaited without failing:
      the probe succeeds once the writer reaches the lock.
- [x] (basic) The five race scenarios of `ProcessingEngineAtomicTransitionsSpec` pass, and 20
      consecutive runs of the spec under CPU load pass.
- [x] (edge) A write that completes without taking the lock fails the probe naming the writer, and the
      message no longer mentions a timeout.
- [x] (edge) A writer that is cancelled before taking the lock does not hang the probe: it fails
      naming the writer.
- [x] (discovered 2026-11-06) A write that throws before taking the lock fails the probe naming the
      writer, with the write's exception kept as the cause.
- [x] (discovered 2026-11-06) Every probe wait is tied to its writer's own completion: `awaitLockCall`
      requires the writer's `Job`, so no probe path is bounded only by `runTest`'s clock, and a writer
      whose Job ends without taking the lock fails at once naming it.
- [x] (discovered 2026-11-06) `ConcurrencySpec` decides its races with the writer-keyed probe and no
      real-time probe bound, its scenarios' assertions are unchanged, and the writer-lock helpers
      exist in one shared copy instead of their diverged per-spec versions.
- [x] (must not break) The five scenarios' assertion lines are unchanged; `CountingMutexSpec` stays
      green with its failure-path test adapted; `gradle check` green.
- [x] (must not break) `queueWrite` keeps its ordering guarantee: the write it returns commits ahead
      of any write launched after the wait.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a test-harness change; the full suite and the loaded runs
above are its proof.

## Workspace log

- Cycle 1 (test-list item 1, late writer): red on `3cf5cab7` + the new test —
  `AssertionError: writer late has not called lock() within 10s` after 10.05 s; green commit
  `8317524f`, the same case green at 11.0 s with no clock in the probe.
- Cycle 2 (test-list item 3, completion without the lock): the `CountingMutexSpec` failure path
  adapted to `queueWrite(lock, "not-yet") { }`; red on `8317524f` + the adapted test —
  `UncompletedCoroutinesError: After waiting for 1m, the test body did not run to completion`; green
  commit `a3f9ee3a` — the case fails in 0.34 s with `writer not-yet finished without calling lock()`.
- Cycle 3 (test-list item 4, cancelled writer): red on `a3f9ee3a` + the new test —
  `Expected exception java.lang.AssertionError but a JobCancellationException was thrown instead`;
  green commit `d2a309fe` — the case fails in 0.02 s naming the writer.
- Refactor (approved, `08caa2d7`): the caught cancellation is marked unused.
- Cycle 4 (test-list item 6, ordering pin): green on arrival at `7bcb217a` (0.023 s); a must-not-break
  pin, so no red is possible without first breaking `queueWrite`.
- Commit `1a25f773`: the stale "waits above bound the real-time probes" comment corrected.
- Test-list item 2 (20 loaded runs): at `1a25f773`, 20 of 20 exit 0, 6 tests / 0 failures each,
  36–266 s, 1262.9 s total, under 8 `yes` hogs on `nproc = 8`.
- Cycle 5 ((discovered), failing writer): red on `0cfcf2d1` + the new test —
  `java.lang.IllegalStateException: boom`; green commit `f9541bb5` — the case fails in 0.012 s naming
  the writer, with `boom` retained in the cause chain. The queued write is isolated under a
  supervisor so a failure reaches the probe instead of cancelling the test scope (a failing `async`
  child otherwise kills the caller before the probe can report it).
- Test-list item 6, the first must-not-break line (final gates, `f9541bb5`; the discovered line
  inserted above it moved it from 5 to 6): `gradle check` green — ktlint clean, 430 tests /
  0 failures / 1 skipped in 152 suites, 2m50s; `git diff main...HEAD` on
  `ProcessingEngineAtomicTransitionsSpec.kt` empty (all assertion lines unchanged);
  `CountingMutexSpec` 6/6 and the spec 6/6. The 20 loaded runs re-ran at this commit: 20 of 20
  exit 0, 6 tests / 0 failures each, 30–58 s, 872.7 s total, under 8 `yes` hogs on `nproc = 8`.

- Review fixes, rebase onto `main` (`12b07d00`, #108) at `1f1644e3`: the import conflicts resolved
  keeping both sides in ktlint order (`ExperimentalCoroutinesApi` plus `Dispatchers`/`async`/
  `awaitCancellation` plus `Job`/`SupervisorJob`); `gradle check` green — 0 compiler warnings, ktlint
  clean, 430 tests / 0 failures / 1 skipped in 152 suites, 3m00s; `CountingMutexSpec` 6/6 and the
  atomic spec 6/6. Two discovered lines added after the root agent's review, wording approved
  2026-11-06: the writer-tied wait for every probe path, and `ConcurrencySpec`'s move to the shared
  writer-keyed helpers. Shapes approved: `awaitLockCall` takes a required `Job` (membership vs
  `onJoin`); the shared helpers live in the `stt.db` test package.
- Cycle 6 (discovered, every probe wait writer-tied): red on `f585852a` + the new test —
  `UncompletedCoroutinesError: After waiting for 1m, the test body did not run to completion` after
  60.085 s; green commit `cd70d3a9` — the same case fails in 0.011 s naming the writer, and the
  cancelled/failed cases keep their cause from the job's completion handler. `awaitLockCall` takes a
  required `Job` and races membership against `job.onJoin`; the null branch and both catch arms are
  gone. `CountingMutexSpec` 7/7, the atomic spec 6/6.
- Refactor (approved, `09eaf28c`): the writer-lock helpers moved from `stt.engine/TestEngine.kt` to
  `stt.db/TestWriterLock.kt`, shared by both spec layers; `ConcurrencySpec`'s private copies were
  renamed first so the shared names could be imported. `CountingMutexSpec` 7/7, atomic spec 6/6,
  `ConcurrencySpec` 3/3, ktlint clean.
- Cycle 7 (ConcurrencySpec scenario 1, held write + insert): red on `09eaf28c` + the 11 s held
  launch — `TimeoutCancellationException: Timed out waiting for 10000 ms` (31.9 s including the held
  transaction's cleanup); green commit `5c74e386` — the insert is awaited through
  `LockWriter`/`awaitLockCall`; scenario 12.66 s, `ConcurrencySpec` 3/3.
- Cycle 8 (accepted decision, scenario 2 reads): the read assertions are unchanged; the 10 s
  `withTimeout` wrapper is removed and scenarios 1–2 use the shared `holdWriteTransaction`; the local
  `LocalHeldWrite`/`localHoldWriteTransaction` are deleted. Green on arrival at `cd5f8e72`:
  `ConcurrencySpec` 3/3 (scenario 0.84 s). No deterministic red exists for a correct read's bound;
  the unchanged assertions and the loaded runs are the proof.
- Cycle 9 (accepted decision, scenario 3 stalled upload): the insert's 10 s `withTimeout` is removed;
  the stall-signal and safety waits stay. Green on arrival at `3a006cb1`: `ConcurrencySpec` 3/3.
  Same accepted-decision proof. Item 2 ticked.
- Final review-fix gates at `cd91dc42`: `gradle --no-daemon check` green — 0 compiler warnings,
  ktlint clean, 431 tests / 0 failures / 1 skipped in 152 suites, 3m20s; `CountingMutexSpec` 7/7,
  the atomic spec 6/6, `ConcurrencySpec` 3/3. Under 8 `yes` hogs on `nproc = 8`: 20 consecutive
  focused `CountingMutexSpec` runs — 20 of 20 exit 0, 7 tests / 0 failures each, 19–20 s, 393.8 s
  total; 20 consecutive focused `ConcurrencySpec` runs — 20 of 20 exit 0, 3 tests / 0 failures each,
  22–43 s, 492.9 s total. Status set to in-review.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, directly from their MED finding
  (CI run 42145117147 #569) and their fix direction, with the writer-keyed signal already in place
  (item 55) and the 10 s bound as the actual cause.
- 2026-11-06: approved (the user's "go"): the signal-only success path with the clock-free named
  failure, the adapted `CountingMutexSpec` failure test and the loaded-run proof; the open question
  (whether the deliberate >10 s hold test may live in the suite) stays for the item's first
  checkpoint. Status set to spec-approved.
- 2026-11-06: first checkpoint (workspace), open question settled: **the deliberate >10 s hold test
  lives in the suite** as a permanent focused test — it holds the writer's dispatcher past the old
  10 s bound, is red on the current probe (the reported `TimeoutCancellationException`) and green
  once the probe has no clock. It costs ~11 s per `gradle check`, in line with the fifth scenario's
  existing 11 s hold (`ProcessingEngineAtomicTransitionsSpec.kt:289`) and well under `runTest`'s
  60 s timeout; 20 loaded runs remain the determinism bound for the schedule-dependent part.
- 2026-11-06 (test-list item 2, 20 loaded runs): at `1a25f773`, 20 consecutive focused
  `gradle test --tests stt.engine.ProcessingEngineAtomicTransitionsSpec --rerun` runs under 8 `yes`
  CPU hogs on `nproc = 8`: 20 of 20 exit 0, 6 tests / 0 failures each, 36–266 s per run (run 1
  includes compilation), 1262.9 s total. A sandbox measurement, not CI.
- 2026-11-06: reviewed by the root agent and approved by the user the same day. The review's notes
  were the log's test-list numbering (`20937583`), the writer-less probe path (cycle 6) and
  `ConcurrencySpec`'s own count-and-clock probe (cycles 7–9). Gates re-run by the root agent at the
  reviewed head `c99c9356`: `gradle check` green — 0 compiler warnings, ktlint clean, 431 tests /
  0 failures / 1 skipped, 2m39s; `ConcurrencySpec` 3/3 (11.3 s, the 11 s hold), `CountingMutexSpec`
  7/7, `ProcessingEngineAtomicTransitionsSpec` 6/6. Squashed for the PR. Status: done.

