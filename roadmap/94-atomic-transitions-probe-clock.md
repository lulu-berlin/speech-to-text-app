# 94 — The atomic-transitions lock probe must not expire on a starved runner

Status: spec-approved
Depends on: [55](../docs/specs/atomic-transitions-lock-probe.md) (done; the writer-keyed probe), [47](../docs/specs/atomic-transitions-spec-flaky.md) and [58](../docs/specs/atomic-transitions-completion-waits.md) (done; the spec's other deterministic waits), [11](../docs/specs/processing-engine.md) (done)
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

- [ ] (basic) A writer that takes its ticket later than the old 10 s bound is awaited without failing:
      the probe succeeds once the writer reaches the lock.
- [ ] (basic) The five race scenarios of `ProcessingEngineAtomicTransitionsSpec` pass, and 20
      consecutive runs of the spec under CPU load pass.
- [ ] (edge) A write that completes without taking the lock fails the probe naming the writer, and the
      message no longer mentions a timeout.
- [ ] (edge) A writer that is cancelled before taking the lock does not hang the probe: it fails
      naming the writer.
- [ ] (must not break) The five scenarios' assertion lines are unchanged; `CountingMutexSpec` stays
      green with its failure-path test adapted; `gradle check` green.
- [ ] (must not break) `queueWrite` keeps its ordering guarantee: the write it returns commits ahead
      of any write launched after the wait.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a test-harness change; the full suite and the loaded runs
above are its proof.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, directly from their MED finding
  (CI run 42145117147 #569) and their fix direction, with the writer-keyed signal already in place
  (item 55) and the 10 s bound as the actual cause.
- 2026-11-06: approved (the user's "go"): the signal-only success path with the clock-free named
  failure, the adapted `CountingMutexSpec` failure test and the loaded-run proof; the open question
  (whether the deliberate >10 s hold test may live in the suite) stays for the item's first
  checkpoint. Status set to spec-approved.
