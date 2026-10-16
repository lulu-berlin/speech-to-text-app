# 55 — The atomic-transitions pin's writer-lock probe is still schedule-dependent

Status: done (roadmap item 55, merged 2026-10-16 as PR #10)
Depends on: — (item [47](atomic-transitions-spec-flaky.md) is done and fixed a different failure
of the same scenario)
Blocks: —

## Goal
The item-47 pin is deterministic under load: its five atomic-transition scenarios no longer wait on an
exact writer-lock call count for a write to reach the writer lock, so a fixed wall-clock probe cannot
time out because another writer's lock call was skipped by a conflated counter — the backend's own
`gradle check` inside a Nix build stays green, a gate whose failure reddens `main`'s `nix build` and
item 50 (the Electron desktop app)'s bundle job. Every scenario's behavioral claims stay asserted
exactly as they are.

## Evidence

- The failure: run 38825990732 (PR #9, head `81b49b09`), job "Desktop (bundle e2e)":
  `nix build .#desktop-bundle` builds the backend with its own `gradle check` (`flake.nix:122`,
  `doCheck = true`, deliberate — nixpkgs' gradle hook would otherwise run only `test`), and the
  spec failed with `kotlinx.coroutines.TimeoutCancellationException at
  ProcessingEngineAtomicTransitionsSpec.kt:277` — 1 of 280 tests, in a 3m19s `:test` run. The same
  spec reddened `main`'s `nix build` in #38650675971 at `:302`; item 47 replaced that exact
  event-count assertion with a bounded drain, and this is a different line in the same scenario.
- `:277` is the second writer-lock probe, `awaitLockCall(lock, base + 2)`: the test holds a layout
  write transaction open (`holdWriteTransaction`, `engine/TestEngine.kt:303-322`), starts the pause
  (`:274`) and then the completion (`fixture.tasks.setCompleted(first, 9_000L)`, `:276`), waits for
  each write to reach the mutex, and only then releases the hold.
- The probe is the fragile part: `awaitLockCall` awaits `lock.lockCalls.first { it == call }` under a
  10 s timeout (`engine/TestEngine.kt:328-333`), and `lockCalls` is a `MutableStateFlow` incremented
  by `lockCalls.update { it + 1 }` in `CountingMutex.lock` (`engine/TestEngine.kt:279-288`).
- **StateFlow conflates, so the awaited exact value can be skipped.** A collector sees the latest
  value, not every value, and concurrent `update` calls coalesce; an awaited intermediate count then
  never matches and the probe ends in `TimeoutCancellationException`.
- **Other writers increment the same counter.** `writeTransaction` takes the one transaction lock
  (`db/Database.kt:69-74`), so while the test holds it, every other writer — notably the engine
  worker's progress and terminal writes for the task under test — queues on the same mutex and
  bumps `lockCalls`. A count therefore also cannot say *which* write queued.
- Hypotheses at drafting time: **(a)** the write never reached `lock()` within the probe (its
  coroutine not dispatched in 10 s, or blocked earlier), or **(b)** it did queue but the counter's
  conflation skipped the awaited value. The consultation settled the mechanism as (b): see
  "Decisions". Item 47 measured its own failure before fixing it; this item's cycle 1 pins (b)
  deterministically instead of hunting the rare CI occurrence.
- Five scenarios of `ProcessingEngineAtomicTransitionsSpec` use the probe (`:49`, `:51`, `:99`,
  `:101`, `:151`, `:153`, `:198`, `:200`, `:275`, `:277`), so one helper change covers all of them,
  and all five are the must-not-break surface.
- Every racing write runs on a test-launched coroutine and is therefore identifiable without touching
  production code: `writeTransaction` calls the lock's `lock()` through `withLock` before its
  `withContext(Dispatchers.IO)` (`db/Database.kt:69-74`), in the caller's coroutine. That caller's `Job`
  is not the launched one for every write, though: `cancelTask` writes inside `withContext(NonCancellable)`
  (`engine/ProcessingEngine.kt:348-354`), whose new `Job` is parented to `NonCancellable`, not to the
  test's coroutine — see the 2026-10-16 amendment in "Decisions". The engine worker's writes run in the
  engine's own scope.
- Branch independence: `backend/src/{main,test}/kotlin/stt/engine/` is byte-identical on `main` and
  on `roadmap/50-electron-app`, so this item branches from `main` and does not wait for item 50.

## Decisions (consultation, 2026-10-16)

- **The mechanism is the probe's exact-value wait (b), and the wait becomes signal-based and
  attributable**: `CountingMutex` records the calling coroutine's `Job` when `lock()` is called, and
  the wait completes when *that specific writer's* job has called `lock()`. Membership in an
  append-only set is monotone, so a conflated collector cannot miss it; and another writer (the
  engine worker) cannot satisfy a wait that belongs to the pause's or the completion's coroutine.
  No production code changes: the fixture observes jobs the tests themselves launch.
- **Both writes are observed the same way**, each by the `Job` of the test-launched coroutine that
  performs it; the completion's write stays the production `setCompleted` call.
- **All five scenarios move to the new wait in the same pass**, including the first probe of each
  pair. Item 47's `pumpUntil(10.seconds)` drain stays: it waits on a monotone event set (duplicates
  and order tolerated), which is a legitimate bounded behavioral wait, not a lock-call probe. The
  probe timeout itself stays as a safety bound that reports which writer is missing.
- **Amendment (workspace kickoff, 2026-10-16, the user): writers are identified by a test-owned
  coroutine-context element, not by their `Job`.** The cancel writes (first probe of the cancel/resume
  and cancel/pause scenarios, second probe of the completion/deletion scenario, via `deleteTask` →
  `cancelTask`) run inside `withContext(NonCancellable)`, so the `Job` seen by `lock()` is neither the
  test-launched job nor a descendant of it, and a Job-keyed wait would hang there every time. A context
  element survives `withContext` (only the `Job` is replaced), and the engine worker's coroutines never
  carry it: each test launches a racer with its own `LockWriter`, `CountingMutex.lock()` adds the caller's
  `LockWriter` to an append-only set, and the wait completes once its writer is in that set. Monotone and
  attributable as decided above; no production change.
- **Cycle 1 is a deterministic fixture-level test, not a reproduction hunt**: with two queued
  writers, the wait for the second must complete even though the counter jumped past the awaited
  value. It is red against today's exact-value wait (it times out) and green with the new wait.
- **`doCheck = true` in `flake.nix:122` stays.** It is the gate that caught this. Whether a
  *packaging* build should run the whole unit suite at all (cost, and the radius of any future
  flake) is a separate decision, filed as a backlog row, not part of this item.
- **Sequencing**: this item lands before item 50's PR merges; PR #9 then rebases and re-runs, so the
  bundle gate is trustworthy rather than lucky.
- **Ownership of the backlog row**: item 50's branch filed the matching `docs/known-issues.md` row
  (Tests section). An issue leaves the backlog the moment a roadmap item plans it, so that row is
  dropped in item 50's next squash; this file is the record.

## Test list (approved 2026-10-16; behavioral scenarios)

- [x] The wait for a queued write is tied to that writer: with the writer lock held and two writers
      queued, each wait completes for its own writer even when the shared counter's value jumped past
      the awaited one, and another writer's lock call cannot satisfy a wait that is not its own.
- [x] The failing scenario's three behavioral claims stay asserted unchanged and green: task A ends
      COMPLETED, no started-PAUSED row is left, and the next task — including its DIARIZING phase —
      runs to completion.
- [x] (must not break) All five `ProcessingEngineAtomicTransitionsSpec` scenarios and the rest of
      `gradle check` stay green, including 20 consecutive loaded runs of the spec while the rest of
      the suite runs.
- [x] (must not break, user-verified) A Nix build of the backend no longer reddens on this spec
      (`nix build .`, and thereby `nix build .#desktop-bundle` with its `doCheck`).

## Acceptance criteria

- No scenario waits on an exact writer-lock call count; each wait completes as soon as its own write
  has queued, with every behavioral assertion of the scenario intact.
- The spec is green in 20 consecutive loaded runs and in the CI nix-build gate.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-16: drafted in the item-50 workspace at the user's request, after the pin failed PR #9's
  bundle job (run 38825990732). Filed as 55 — 54 is the highest number in use.
- 2026-10-16: consultation (main tree). Mechanism settled as the probe's conflated exact-value wait,
  with the engine worker's writes also incrementing the shared counter; the fix is a Job-attributed,
  monotone wait in the fixture, covering all five scenarios, with no production change. Cycle 1 is a
  deterministic fixture test (red against the exact-value wait). `doCheck = true` stays; the
  packaging-check scope question goes to the backlog. Sequencing: item 55 before item 50's merge.
  Test list approved as worded above; status `spec-approved`.
- 2026-10-16: workspace kickoff (`workspaces/55-atomic-transitions-lock-probe`). The code contradicted
  the Job-keyed design for the three cancel writes (`withContext(NonCancellable)`); the user chose a
  test-owned context element as the writer identity (Decisions, amendment). Cycle 1 confirmed: a new
  `CountingMutexSpec` — two writers queued behind a held lock before either wait starts, each wait
  completes for its own writer, a wait for a writer that has not called `lock()` times out naming it;
  the hang red is captured with today's exact-count wait at the full 10 s probe bound.
- 2026-10-16 (cycle 1, test-list item 1, `CountingMutexSpec`): red — the scenario written against today's
  exact-count wait (both writers queued by `runCurrent()`, `lockCalls == base + 2` asserted and passing, then
  `awaitLockCall(lock, base + 1)` for the first writer) failed with `kotlinx.coroutines.TimeoutCancellationException:
  Timed out waiting for 10000 ms` at `CountingMutexSpec.kt:24` after 10.142 s, the full probe bound. The test
  in its final form (writer-keyed waits) then failed to compile: `Unresolved reference 'LockWriter'`, `No
  parameter with name 'timeout' found`. Green: `LockWriter` (a coroutine-context element), `CountingMutex.writers`
  (an append-only `StateFlow<Set<LockWriter>>`, added to on every `lock()` whose caller carries a writer) and
  `awaitLockCall(lock, writer, timeout)`, which completes once the writer is in the set and otherwise throws
  an `AssertionError` naming it. The count-based `awaitLockCall` stays for now; the five scenarios still
  use it. Spec green (1 test, 0.346 s); `gradle check` green (ktlint clean, 303 tests in 110 specs, 0 failures, 50 s).
- 2026-10-16 (refactor checkpoint after cycle 1): no refactoring, agreed with the user; item 1 ticked.
- 2026-10-16 (cycle 2, test-list item 2): no red of its own — the scenario fails only by rare scheduling, and
  cycle 1 is its deterministic red. All ten probes of `ProcessingEngineAtomicTransitionsSpec` now launch each
  racer with its own `LockWriter` (`completion`, `pause`, `cancel`, `resume`, `delete`) and wait with
  `awaitLockCall(lock, writer)`; the `base` count reads and the count-keyed `awaitLockCall` are gone. The
  diff removes only probe lines and one comment: no assertion changed, and item 47's `pumpUntil(10.seconds)`
  drain stays. The three cancel writes (inside `withContext(NonCancellable)`) satisfied their waits, which
  shows the context element survives there. Spec green (6 tests; the five race scenarios at 1.53/0.19/0.14/0.17/0.23 s),
  `CountingMutexSpec` green; `gradle check` green (ktlint clean, 303 tests in 110 specs, 0 failures, 48 s).
- 2026-10-16 (refactor, approved after cycle 2): `queueWrite(lock, name) { … }` in `engine/TestEngine.kt`
  launches a racer on Dispatchers.Default with a fresh `LockWriter` and returns once that writer has called
  `lock()`; each of the ten racers is one line, and a probe can no longer be paired with another racer's
  writer. Behavior unchanged (same dispatcher, wait and bound). `gradle check` green (ktlint clean, 303
  tests, 0 failures, 50 s). Item 2 ticked.
- 2026-10-16 (test-list item 3, must not break; run form chosen by the user): 20 consecutive full-suite runs
  in the sandbox at head `701976e9` (`gradle test --rerun`, so the spec runs under the rest of the suite's
  load, as in the Nix build's `gradle check`): 20 of 20 exit 0, 303 tests and 0 failures each time; the
  spec 6 tests, 0 failures in every run (0.80–1.02 s per run, 44–46 s per suite run, 902 s in total).
  With `gradle check` green after cycle 2 and the refactor, item 3 ticked. This is a sandbox measurement,
  not CI; item 4 (the Nix build) is the user's run.
- 2026-10-16 (test-list item 4, the user's run): `nix build ./workspaces/55-atomic-transitions-lock-probe`
  at head `8ba75301` succeeded with no output, and `result` now points at
  `/nix/store/ycbgg48d14ls7wjyap41jrgjh33hvq1p-stt-app-0.1.0` (link written 02:34 UTC). The backend
  derivation's `src` includes `./backend` (`flake.nix:75`), so this item's test changes forced a rebuild with
  `doCheck = true`/`gradleCheckTask = "check"` (`flake.nix:121-122`). `.#desktop-bundle` exists only on item
  50's branch and is exercised when PR #9 rebases onto this item. Item 4 ticked; all items green; status
  `in-review`.
- 2026-10-16 (root-agent review; diff approved by the user): no blocking findings; the five scenarios'
  assertion lines are unchanged against `main`. Two approved follow-ups: (1) the probe records a writer just
  before its call joins the mutex queue, a pre-existing gap (item 40's counter had it) now logged in
  `docs/known-issues.md` § Tests, with `TestEngine.kt`'s probe comments no longer claiming "is queued";
  (2) `CountingMutex.lockCalls` dropped — only `CountingMutexSpec` read it — and that spec now shows its
  precondition as both writers recorded (`writers == {first, second}`) before either wait starts.
- 2026-10-16: merged on GitHub as PR #10 (`113ed428`, one squashed commit). Spec and final test list moved
  to `docs/specs/atomic-transitions-lock-probe.md`; status done. Item 50's `desktop-bundle` job verifies it
  once PR #9 rebases onto `main`.
