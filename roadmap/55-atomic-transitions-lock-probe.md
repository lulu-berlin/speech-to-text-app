# 55 — The atomic-transitions pin's writer-lock probe is still schedule-dependent

Status: spec-approved
Depends on: — (item [47](../docs/specs/atomic-transitions-spec-flaky.md) is done and fixed a different failure
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
- Both writes of the failing scenario run on test-owned coroutines and are therefore identifiable
  without touching production code: `pauseTask` writes through `command(...)`/`pauseIfPossible`
  (`engine/ProcessingEngine.kt:214-226`) and `setCompleted` through `updateTask`
  (`db/TaskRepository.kt:273-282`); `writeTransaction` uses `withContext(Dispatchers.IO)`, which
  keeps the caller's `Job`. The engine worker's writes carry a different `Job`.
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

- [ ] The wait for a queued write is tied to that writer: with the writer lock held and two writers
      queued, each wait completes for its own writer even when the shared counter's value jumped past
      the awaited one, and another writer's lock call cannot satisfy a wait that is not its own.
- [ ] The failing scenario's three behavioral claims stay asserted unchanged and green: task A ends
      COMPLETED, no started-PAUSED row is left, and the next task — including its DIARIZING phase —
      runs to completion.
- [ ] (must not break) All five `ProcessingEngineAtomicTransitionsSpec` scenarios and the rest of
      `gradle check` stay green, including 20 consecutive loaded runs of the spec while the rest of
      the suite runs.
- [ ] (must not break, user-verified) A Nix build of the backend no longer reddens on this spec
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
