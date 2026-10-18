# 60 — Exception-handling policy: no catch-all swallows a throwable silently

Status: done (roadmap item 60, approved 2026-10-18)
Depends on: [42](worker-stability.md) (done; this item starts after 42 lands, because
its guard reworks the same failure path)
Blocks: —

## Goal

Every `catch` of a broad type (`Exception`, `Throwable`) in production code follows an explicit
policy — wrap and rethrow, narrow to the expected type, or degrade with the throwable logged — so no
failure is swallowed silently and no stack trace is lost. The catch-all sites found by the user's
grep (`grep -ir ": Exception" backend/src`, 2026-10-17) are audited below with a verdict each; the
item fixes the sites whose behavior loses information, and leaves a rule that keeps new ones from
appearing.

## Evidence (audit of every site, read 2026-10-17)

Sites that already follow the policy — no action, recorded so the audit is durable:

- `db/Database.kt:118,142,167` (+ the `cause: Exception` parameter at `:177`): a migration, connect
  or pragma failure closes the connection and is rethrown as `IllegalStateException` naming
  `STT_DATA_DIR`, original cause preserved. Wrapper-rethrow; correct.
- **Sequencing (decided 2026-10-17, the user)**: this item starts after item 42 lands. The user's
  observation came from item 42's work, and the item-42 agent was told to avoid the anti-pattern in
  its own changes; the audit's dispositions here then apply to the reworked failure path on top of
  42's guard, with no concurrent edits to `ProcessingEngine.kt`.
- `diarization/SpeakerModel.kt:49`: a failed metadata parse closes the ONNX session and rethrows.
  Cleanup + rethrow; correct.
- `process/ProcessRunner.kt:93` (`catch (e: Throwable)`): any failure cancels the pipes, terminates
  the process tree under `NonCancellable`, and rethrows. Cleanup + rethrow; correct — and
  deliberately `Throwable`, so a callback's `Error` still kills the tree.
- `db/StagedUpload.kt:60` (`catch (e: Throwable)`): a failed staged read deletes the unclaimed
  original and rethrows (a canceled caller included). Cleanup + rethrow; correct.
- `http/InternalError.kt:27` (`catch (cause: Throwable)`): rethrows `CancellationException`, logs the
  throwable with the request path, responds 500; if the response is already committed it rethrows the
  original. Correct.
- Type definitions, not catch sites: `audio/FlacSplitter.kt:45` (`NotCanonical`), `http/EventsRoutes.kt:132`
  (`FellBehindException`). No action.

## Decided (consultation, 2026-10-17)

- **Starts after item 42 lands** (the user): the observation came from item 42's work, and the
  item-42 agent was told to avoid the anti-pattern in its own changes; this item's engine change
  then applies to the reworked failure path on top of 42's guard, with no concurrent edits to
  `ProcessingEngine.kt`.
- **A failed device probe is reported as a failed probe, never as "no GPU"** (the user, 2026-10-17:
  "If a device probe fails, the user should know that the device probe failed and not be told the
  false claim that there is no GPU"). Today the user is told the false claim: `gpus()` catches any
  `Exception` and returns `emptyList()`; `Main.kt:20` logs "Device probe: 0 GPU(s)"; `settings.json`
  then carries `availableDevices: ["cpu","auto"]` and `gpuNames: {}` — identical to a genuinely
  GPU-less machine — and `SettingsView` shows no notice (`DeviceFallbackBanner` is the *runtime*
  CPU fallback only). The fix: the probe returns its outcome (devices, or the failure with its
  message), the app starts with CPU/auto, and the settings contract carries the failure
  (an additive field, e.g. `deviceProbeError`), shown as a callout. A successful probe with zero
  devices stays a true "no GPU" and shows no error.
- **The failure notice lives on the Settings page** (the default stated at the consultation;
  extending it to the queue view can be a follow-up if wanted).
- **Only launch failures (`IOException`) are reported; any other `Throwable` fails startup** (the
  user: "a bug should become a blocker, otherwise we might miss it"): whisper-cli missing or not
  executable is `IOException` and becomes the reported probe failure; anything else propagates and
  crashes startup loudly. `CancellationException` stays rethrown. The class KDoc and
  `DeviceProbeSpec`'s comments follow.
- **A grep-based static check in the repo's Node checks** (the user): a script beside
  `docs/check-links.mjs` scans `backend/src/main/kotlin` for `catch (…: Exception)`, `catch
  (…: Throwable)` and `runCatching`, and fails unless the catch rethrows or passes the throwable to a
  logger; an explicit marker comment (`// exceptions: swallow-ok <reason>`) allowlists a deliberate
  degradation; `runCatching` in production code fails outright (both current uses are narrowed or
  checked in this item's cycles, so none should remain). Wired into CI and documented in AGENTS.md
  § Commands; the check's own test-list item pins its red (a planted swallow) and its green (the
  clean tree).
- **Pin the probe's failure reporting** (the user): `DeviceProbeSpec` (and the route/UI tests) pin
  the reported failure and the true zero-GPU case; the audited wrapper-rethrow sites stay as the
  recorded audit, untested.

Sites that swallow or lose information — this item's work (still under Evidence; the Decided section
above records the one decision made so far):

- **The task-failure path, as item 42 reworked it** (`runTask` → `recordFailure`, `ProcessingEngine.kt:602-615`
  on 42's branch; `catch (e: Exception)` at `:501` on `main` today): item 42 already logs the write's
  refusal with both throwables (`addSuppressed`), which is what this item's MED row asked for; what
  remains is that a refusal the repository reports as `false` (the failure write lost to a cancel, a
  pause or a completion) is still recorded and logged nowhere. **Two findings from item 42's main-tree
  review (2026-10-18, the user: they belong here):** (a) the refusal's log line says the task "keeps
  its status (a RUNNING task waits for a restart's recovery)", which is right for RUNNING but hides
  that a QUEUED task whose start failed is picked up again at once — if the failure write is refused
  as well, the worker spins on it (ADR 0058's Consequences record the case; no test covers it);
  (b) the worker's next-task read guard (`nextTaskOrNull`, `:454-462`) catches `Exception`, while the
  phase guard catches `Throwable` (item 42's decision that an `Error` from a phase fails the task), so
  an `Error` from the reads still ends the worker loop — the policy this item writes has to say which
  type each guard takes, and align them deliberately.
- `transcribe/DeviceProbe.kt:38` (`catch (e: Exception)`): any probe failure becomes an empty list
  with a warn line carrying only `e.toString()` — no stack. The result is then presented as "no
  GPU": `Main.kt:20` logs "Device probe: 0 GPU(s)"; `http/SettingsRoutes.kt` returns
  `availableDevices: ["cpu","auto"]` and `gpuNames: {}` (identical to a GPU-less machine); and
  `frontend/src/features/settings/SettingsView.tsx` shows the device select with no notice
  (`DeviceFallbackBanner` covers only the runtime CPU fallback). The probe correctly rethrows
  `CancellationException` first; the rest is the false-claim path.
- `engine/ProcessingEngine.kt:748` (`removeScratch`, `catch (e: Exception)`): best-effort cleanup
  (deliberate), but the warn line carries only `e.toString()` — the stack is lost.
- `diarization/SpeakerIds.kt:44` (`catch (e: Exception)` around the summary callback): logs the
  throwable itself and continues. This is the house pattern working; audit-approve, no change.
- `http/SettingsRoutes.kt:110` (`runCatching { parseStoredDevice(device) }.getOrNull()`): expected
  control flow for a bad `device` value in a `PUT /api/settings` request, but `runCatching` discards
  *every* `Throwable` — a bug in the parser would be indistinguishable from bad input. A corrupted
  stored row is not this path: `SettingsRepository.get` parses it and fails fast. Narrowing candidate.

Related shape checked: the engine's phase guard rethrows `CancellationException` before its
`catch (e: Throwable)` (item 42: an `Error` from a phase fails the task); the item-42 read guard
`nextTaskOrNull` rethrows it before a `catch (e: Exception)` — the asymmetry finding (b) above. No
`runCatching` in production code wraps suspending calls (the two uses above are pure functions).

## Proposal

For each swallowing site, apply the minimal policy that keeps its deliberate behavior but keeps the
information: the task-failure path logs the throwable with the task's MDC context (item 46) in
addition to the DB write, and logs when `failIfRunning` loses the race; `removeScratch` logs the
throwable, not `e.toString()`; `SettingsRoutes` narrows `runCatching` to the expected exception type.
The device probe stops inventing a result: `DeviceProbe` returns its outcome (the device list, or
the launch failure and its message), `Main` passes it to the app, the additive `deviceProbeError`
field carries it in the settings JSON (the contract examples in `docs/api/settings*.json` follow),
and the Settings page shows a callout naming the failed probe — while a successful zero-GPU probe
stays silent and true. `IOException` is the reported failure; any other `Throwable` propagates and
fails startup.
Record the per-site dispositions in this file as the policy's examples. The static guard question is
decided: yes, the grep-based check above.

## Test list (approved 2026-10-17; behaviors only)

- [x] A task that fails mid-run logs its throwable (full stack) with the task's MDC context, in
      addition to the persisted record
- [x] When the failure write itself loses the race to a cancel, the loss is logged (task id, cause)
- [x] When a QUEUED task's start write fails and the write recording that failure is refused as well,
      the refusal is logged with the task's id and both errors, and names the status the task keeps —
      QUEUED, which the worker retries at once — never "RUNNING until a restart recovers it"
- [x] An `Error` thrown by the worker's next-task reads does not end the worker: it is logged with the
      read failure's kind and the worker takes the next queued task once the reads succeed
- [x] A device probe whose process cannot be launched (an `IOException`) reports a failed probe with
      the failure's message, never an empty device list
- [x] A probe that succeeds with zero devices reports a successful empty list — no failure (the true
      "no GPU")
- [x] After a failed probe, the settings JSON carries the failure (`deviceProbeError`) and still offers
      CPU/auto, with no GPU names
- [x] The Settings page shows a callout naming a failed probe, and none for a successful one
- [x] Any other `Throwable` from the probe propagates and fails startup
- [x] (e2e, ADR 0046) Against the real backend launched without a usable whisper-cli, the Settings
      page shows the probe-failure notice; with whisper-cli present, it shows none
- [x] A failed scratch-directory cleanup logs the throwable
- [x] A `PUT /api/settings` with a bad `device` value (`tpu`, `gpu:-1`, `gpu:x`, `gpu`) is still
      rejected as `invalid-device`, with the parser's expected exception caught by type rather than by
      a catch-all
- [x] (discovered) `InternalError`'s `runCatching` — a third production use the audit missed — keeps the
      original exception as the one that surfaces: a response that cannot be written is attached to it
      as suppressed and the original is rethrown, so the clean tree has no `runCatching`
- [x] The static check fails when a broad catch that neither rethrows nor logs the throwable (or a
      `runCatching`) is planted in production code, passes on the clean tree, and is wired into CI
      and AGENTS.md § Commands
- [x] (must not break) All other audited behaviors stay: the runtime CPU-fallback banner, best-effort
      scratch cleanup, a non-fatal speaker-summary failure, the 500 on unhandled errors, a staged
      upload discarded on a failed or canceled read

## Acceptance criteria

- Every broad catch in production code rethrows, narrows, or degrades with the throwable logged — and
  a failed device probe is reported as a failure, never as "no GPU"; the audited sites' dispositions
  are recorded here as the policy's examples.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-17: filed at the user's request from their `: Exception` grep. The audit above (every
  site read in context) is the item's evidence: three real loss-of-information sites plus one
  narrowing candidate; the rest are wrapper-rethrow sites or type definitions recorded as correct.
  Not yet consulted.
- 2026-10-17: consultation — the user decided: starts after item 42 lands; `DeviceProbe` narrows to
  `IOException` (a bug should become a blocker); the static guard is a grep-based check beside
  `docs/check-links.mjs`, wired into CI and AGENTS.md § Commands; only the probe's degradation is
  pinned by a test. Test list updated (proposed; awaiting the user's approval). This file is filed
  on `main` through the next docs PR; the MED backlog row it plans
  (`docs/known-issues.md`, "Task-failure path never logs the throwable", `ProcessingEngine.kt:501-506`)
  leaves the backlog in that PR.
- 2026-10-17: probe follow-up (the user): continuing with CPU only is reasonable, but the indication
  must reflect reality — a failed probe is reported as a failed probe (settings JSON field + Settings
  page callout), never as "no GPU"; a successful zero-GPU probe stays silent. The Decided and Test
  list above are already in this form; the earlier "pin the degradation" wording is superseded by
  "pin the failure reporting".
- 2026-10-17: spec and test list approved by the user as worded above. Status: spec-approved. Filed
  on `main` through a docs PR (with the MED backlog row removed); its workspace starts after item 42
  lands.

- 2026-10-18: amended from item 42's main-tree review, at the user's direction ("exception handling
  should be done in roadmap item 60"): the task-failure bullet is re-anchored to 42's reworked code
  (`recordFailure`, which now logs the refusal with both throwables) and names what 42 leaves (the
  silent refusal-as-`false`), plus two findings — the refusal log line's misleading status wording
  and the queued task's spin when the failure write is refused too, and the read guard's
  `Exception`-only catch where the phase guard takes `Throwable`. Item 42's branch records both
  findings and drops the two backlog rows this item would otherwise have needed. The approved test
  list is unchanged: the test-list items for (a) and (b) are agreed when this item starts, after 42
  lands. Status stays spec-approved.

- 2026-10-18: start of the item. The test-list items for item 42's two review findings are approved
  and added above (the refusal log's status wording for a QUEUED task; an `Error` from the worker's
  next-task reads). Decisions confirmed: both guards take `Throwable` (after the
  `CancellationException` rethrow), the QUEUED task's at-once retry stays a recorded residual, and
  the static check is `docs/check-exceptions.ts` run by Node's type stripping (ADR 0054, item 50's
  pattern). First cycle: "A task that fails mid-run logs its throwable …".

- 2026-10-18: cycle 1 (test-list item 1). `ProcessingEngineFailureSpec`'s first scenario captures the
  engine's ERROR logs for the run: one event, `taskId` in its MDC, and the original
  `TranscriptionException` as the logged throwable, whose top stack frame is where the test threw it;
  the stored FAILED record is unchanged. `runTask`'s `catch (e: Throwable)` now logs the failure
  before recording it. The item-42 refusal scenario in the same spec now expects two ERROR events
  (the task's failure, then the refused write) and asserts both, the refusal still carrying the
  original as suppressed. Red: `gradle test --tests 'stt.engine.ProcessingEngineFailureSpec' --rerun`
  → `org.opentest4j.AssertionFailedError: Collection should have size 1 but has size 0. Values: []`
  (`ProcessingEngineFailureSpec.kt:52`); after the fix the item-42 scenario failed at its old
  `events shouldHaveSize 1` (`:138`) and was updated to the two events above, not weakened. Green:
  `gradle check` exit 0 (113 suites, 318 tests, 0 failures — ktlint + unit).

- 2026-10-18: cycle 2 (test-list item 2). `ProcessingEngineFailureSpec` "a task whose chunk fails after
  its cancel has been stored": two chunks, the second held; `cancelIfPossible` stores CANCELED while
  the chunk's process still runs, then the held transcription throws `TranscriptionException("boom")`.
  Expected: the task stays CANCELED with no error and the cancel's `finishedAt`; after the task-failure
  log, the engine logs the refused failure write with `taskId` in the MDC and the failure as its
  throwable. Red: `gradle test --tests 'stt.engine.ProcessingEngineFailureSpec' --rerun` →
  `kotlinx.coroutines.TimeoutCancellationException: Timed out after 1s of _virtual_ (kotlinx.coroutines.test)
  time …` at `ProcessingEngineFailureSpec.kt:180` — the second log never came. Green: `recordFailure`
  logs the `false` return ("the task kept a newer status") with the failure; `gradle check` exit 0
  (113 suites, 319 tests, 0 failures — ktlint + unit).

- 2026-10-18: cycle 3 (finding (a)'s test-list item). `ProcessingEngineFailureSpec` "a queued task whose
  RUNNING write and the write recording its failure are both refused": triggers refuse both transitions,
  so the task never leaves QUEUED; the worker's first failure log, the refusal log and the next
  attempt's failure log are then received with no wakeup. Expected: the refusal names `stays QUEUED`
  and never `RUNNING`, carries `taskId` in the MDC and the original error as suppressed, and the task
  is still QUEUED. Red: `gradle test --tests 'stt.engine.ProcessingEngineFailureSpec' --rerun` →
  `AssertionFailedError: "Could not record task 1's failure; it stays RUNNING until a restart recovers
  it" should include substring "stays QUEUED"` (`ProcessingEngineFailureSpec.kt:229`). Green: `runTask`
  records whether the run passed its start write, and `recordFailure` names the status the task kept;
  `gradle check` exit 0 (113 suites, 320 tests, 0 failures). Also in this cycle: cycle 2's second wait
  no longer uses `withTimeout(1.seconds)` — the virtual timeout can fire before the engine's real
  `Dispatchers.IO` write lands (observed in this cycle's first focused run) — it is a plain
  `errors.receive()`; the recorded cycle-2 red stands.

- 2026-10-18: refactor after cycle 3 (approved): `refuseStatusWrite(db, task, status)` in
  `ProcessingEngineFailureSpec` builds the `BEFORE UPDATE … RAISE(ABORT, 'disk I/O error')` trigger once,
  used by the three refusal scenarios (the FAILED write, the RUNNING write, and both). `gradle check`
  exit 0 (113 suites, 320 tests, 0 failures).

- 2026-10-18: cycle 4 (finding (b)'s test-list item). `ProcessingEngineWorkerLoopSpec` "an idle engine
  whose first next-task lookup throws an Error": a new internal seam,
  `ProcessingEngine.beforeNextTaskLookup` (null in production, like `beforeTaskStarts`), throws a one-shot
  `NoClassDefFoundError`; expected: the lookup failure is logged as "an unexpected failure" with the
  `Error` itself, and the worker runs the next queued task once the reads succeed. Red: `gradle test
  --tests 'stt.engine.ProcessingEngineWorkerLoopSpec' --rerun` → `UncompletedCoroutinesError: After
  waiting for 1m, the test body did not run to completion` (`ProcessingEngineWorkerLoopSpec.kt:46`) —
  the `Error` ended the worker. Green: `nextTaskOrNull` catches `Throwable` after the
  `CancellationException` rethrow, matching the phase guard, and `reasonOf`/`loggedLookupFailure` take
  `Throwable`; `gradle check` exit 0 (113 suites, 321 tests, 0 failures).

- 2026-10-18: cycle 5 (the scratch-cleanup item). `ProcessingEngineFailureSpec` "a task whose scratch
  directory cannot be removed": two chunks, the second held; the test writes `kept.txt` into the task's
  scratch directory and makes the directory read-only while the run finishes, so the cleanup cannot
  delete the file. Expected: exactly one WARN naming the task and directory, carrying the
  `AccessDeniedException` as its throwable (not `e.toString()`), with the task still COMPLETED and the
  file left in place. Red: `gradle test --tests 'stt.engine.ProcessingEngineFailureSpec' --rerun` →
  `AssertionFailedError: Expected value to not be null, but was null.`
  (`ProcessingEngineFailureSpec.kt:389`) — the warn carried the string only. Green: `removeScratch`
  logs the throwable; `gradle check` exit 0 (113 suites, 322 tests, 0 failures).

- 2026-10-18: refactor after cycle 5 (approved): `holdSecondChunk(fixture)` in
  `ProcessingEngineFailureSpec` sets up the two-chunk run with its held second transcription once, used by
  the cancel-then-failure, SIGTERM-while-transcribing, app-quit and scratch-cleanup scenarios. `gradle
  check` exit 0 (113 suites, 322 tests, 0 failures).

- 2026-10-18: the bad-device test-list item reworded at the user's direction: it is a `PUT
  /api/settings` with a bad `device` value (the request path, pinned by `SettingsUpdateSpec`), not a
  stored row — a corrupted stored row fails fast instead. The user approved the reworded item and its
  green-on-arrival cycle.

- 2026-10-18: cycle 6 (the reworded bad-device item; green on arrival, as forecast). The existing
  `SettingsUpdateSpec` covers `tpu`, `gpu:-1`, `gpu:x` and `gpu`: `gradle test --tests
  'stt.http.SettingsUpdateSpec' --rerun` exit 0 before and after the change. `SettingsRoutes.validate`
  now catches only `IllegalArgumentException` around `parseStoredDevice`; any other throwable
  propagates to the 500 handler. `gradle check` exit 0 (113 suites, 322 tests, 0 failures).

- 2026-10-18: cycle 7 (the split probe item: the failed launch). `DeviceProbe` returns
  `DeviceProbeOutcome` (`Probed` / `LaunchFailed`); `DeviceProbeSpec`'s missing-executable scenario
  expects `LaunchFailed` with the executable's message, and the two GPU scenarios expect
  `Probed(...)`, so a zero-GPU success stays distinguishable. The internal constructor's runner is a
  `suspend (List<String>) -> ProcessResult` seam (the public constructor is unchanged), and
  `ExecutableNotFoundException` now extends `IOException`: that is the runner's actual launch-failure
  type — the spec's assumption that it was an `IOException` was wrong. `DeviceProbe` logs the launch
  failure with its throwable; `Main` logs the summary and passes the GPUs it could read
  (`deviceProbeError` wiring follows with the settings item). Red: first a compile red for
  `probe()`/`DeviceProbeOutcome`; with the outcome in place the focused spec failed with
  `stt.process.ExecutableNotFoundException … at DeviceProbeSpec.kt:78` — the launch failure escaped
  instead of being reported. Green: `gradle integrationTest --tests 'stt.transcribe.DeviceProbeSpec'
  --rerun` exit 0; `gradle check` exit 0 (113 suites, 322 tests, 0 failures).

- 2026-10-18: cycle 8 (the settings-JSON item; the zero-GPU item was ticked from cycle 7's evidence —
  its scenario already asserts `Probed(emptyList())` and stays green). `SettingsJson` gains the
  additive `deviceProbeError` (null for a successful probe), threaded from `Main`'s `LaunchFailed`
  through `sttApp`/`settingsRoutes`/`toJson`; `docs/api/settings.json`, `settings.fallback.json` and
  `settings.fixed.json` carry the null field, and the new `settings.probe-failed.json` carries the
  message with `availableDevices: ["cpu","auto"]` and no GPU names. `SettingsRoutesSpec` "the app
  started after a failed device probe" compares GET with the new example. Red: `gradle test --tests
  'stt.http.SettingsRoutesSpec' --rerun` → `AssertionFailedError: Values differed at keys …
  "deviceProbeError" = "Executable not found: whisper-cli" …` (`SettingsRoutesSpec.kt:90`), and
  `SettingsUpdateSpec`'s by-hand expected body needed the null field. Green: `gradle test --tests
  'stt.http.Settings*Spec' --rerun` exit 0; `gradle check` exit 0 (113 suites, 323 tests, 0 failures).

- 2026-10-18: refactor after cycle 8 (approved): `Main` matches the probe outcome once and destructures
  `(gpus, deviceProbeError)` instead of a `when` plus a second `as?` observation. `gradle check` exit 0
  (113 suites, 323 tests, 0 failures).

- 2026-10-18: rebased onto `main` `2504b34b` at the user's request (item 58's completion waits). No
  conflicts; `gradle check` exit 0 after the rebase (114 suites, 325 tests, 0 failures).

- 2026-10-18: cycle 9 (the Settings-page callout item). `Settings` carries `deviceProbeError` (omitted
  from `SettingsUpdate` and `SettingsNotice`, like `gpuNames`); `settingsMachine` preserves it across
  `settings-updated` and a rejected save; `SettingsView` renders
  `<ErrorCallout title="The device probe failed" detail=…>` when it is set. Tests: the
  `docs/api/settings.probe-failed.json` fixture shows the callout with the probe's message, and the
  successful `settingsFixed` fixture shows none. Red: `node node_modules/vitest/vitest.mjs run
  src/features/settings/SettingsView.test.tsx` → `TestingLibraryElementError: Unable to find
  role="alert"` (`SettingsView.test.tsx:107`). Green: vitest 36 files / 175 tests, prettier clean,
  `tsc --noEmit` clean.

- 2026-10-18: cycle 10 (the probe-Throwable item; green on arrival, as forecast). New unit spec
  `DeviceProbeErrorSpec` uses the runner seam to throw a `NoClassDefFoundError`; `probe()` propagates it
  unchanged. Cycle 7's narrowing to `IOException` is what the test pins (a broad catch would swallow
  it), so there was no production change. Green: `gradle test --tests
  'stt.transcribe.DeviceProbeErrorSpec' --rerun` exit 0 on arrival; `gradle check` exit 0 (115 suites,
  326 tests, 0 failures).

- 2026-10-18: cycle 11 (the e2e item; ADR 0046). `startBackend` takes an optional `env` override (merged
  before the fixed data dir/port/models). New `e2e/tests/60-device-probe.spec.ts` starts its own backend
  on a free port and a fresh data dir with the whisper-cli directory removed from `PATH`, reads
  `GET /api/settings` (non-empty `deviceProbeError`, `availableDevices: ["cpu","auto"]`, no GPU names),
  opens its `/settings` and asserts the "The device probe failed" callout repeats that message; it then
  stops that backend and asserts the shared backend's `/settings` shows no notice (whisper-cli present).
  Run: `node node_modules/@playwright/test/cli.js test tests/60-device-probe.spec.ts` from `e2e/` → 1
  passed (37.0 s); the second backend's log shows the WARN with the full
  `ExecutableNotFoundException` stack. The shared backend was not disturbed (separate port and data
  dir; `stopBackend` finds the app JVM by its data dir).

- 2026-10-18: cycle 12 (the discovered `InternalError` item; green on arrival, as forecast).
  `internalErrorHandler`'s `runCatching { respond(500) }.getOrElse { throw cause }` became a `try`/`catch
  (response: Throwable)` that adds the failed write to the original as suppressed and rethrows the
  original — the same behavior with the response failure preserved and no `runCatching` left in
  production code. `gradle test --tests 'stt.http.InternalErrorSpec' --rerun` exit 0 before and after;
  `gradle check` exit 0 (115 suites, 326 tests, 0 failures).

- 2026-10-18: cycle 13 (the static-check item). `docs/check-exceptions.ts` (Node 24 type stripping,
  ADR 0054) scans `backend/src/main/kotlin` with comments and string contents blanked:
  `runCatching` is always a violation; `catch (x: Exception|Throwable)` passes when its body rethrows,
  passes the caught throwable to a logger (`logger`, `log`, `*Logger`), or carries
  `// exceptions: swallow-ok <reason>`. The one marker is `nextTaskOrNull`, whose catch delegates to
  `loggedLookupFailure`. `docs/check-exceptions.test.ts` (`node --test`) covers the planted swallow,
  rethrow, logging, marker, `runCatching`, a narrow catch and the clean production tree. Wired as the
  `docs-check` CI job (the check and its tests) and in AGENTS.md § Commands. Red: `node --test
  docs/check-exceptions.test.ts` → 7 of 7 failed, `Cannot find module …/docs/check-exceptions.ts`;
  with the script in place the clean-tree case failed on the real `SpeakerIds`/`nextTaskOrNull` shapes,
  which produced the named-logger fix and the marker. Green: the check, its 7 tests, the three docs
  checks and `gradle check` exit 0 (115 suites, 326 tests, 0 failures).

- 2026-10-18: cycle 14 (the must-not-break item; the whole item's final verification). Backend:
  `gradle check` exit 0 (115 suites, 326 tests, 0 failures) and `gradle integrationTest` exit 0
  (138 specs, 375 tests, 0 failures). Frontend: `vitest run` 36 files / 175 tests passed, prettier
  `--check` clean, `tsc --noEmit` clean. Docs: `node docs/check-exceptions.ts`, its 7 tests,
  `check-links`, and both architecture checks pass. E2E: the full suite passed (23 tests, 9.3 min,
  the new `60-device-probe.spec.ts` included; its second backend logged the probe's WARN with the full
  stack). The audited behaviors — the CPU-fallback banner, best-effort scratch cleanup, a non-fatal
  speaker summary, the 500 body, a staged upload discarded on a failed or canceled read — are covered
  by those suites and stay green. Every test-list item is ticked; Status: in-review. The Nix and
  packaged checks are the user's.

- 2026-10-18: rebased onto `main` `ac704d22` at the user's request (item 41's upload hardening). No
  conflicts; after the rebase `gradle check` exit 0 (115 suites, 327 tests, 0 failures) and frontend
  `vitest run` 36 files / 175 tests passed. Status stays in-review.

- 2026-10-18: main-tree review fixes (three; the e2e/CI `which whisper-cli` question is deliberately
  left to the PR's CI run). Status stays in-review.
  (1) The check now gates merges: `docs-check` is in the `ci` aggregator's `needs`. `actionlint
  .github/workflows/ci.yml` is clean; it also caught that the `docs-check` job's `node-toolchain`
  needs the required `cache-dependency-path`, supplied as `frontend/pnpm-lock.yaml`.
  (2) `docs/check-exceptions.ts` no longer accepts the two stack-losing shapes: a logger call counts
  only when the caught throwable is an argument expression itself, so `logger.warn("failed: {}",
  e.toString())` fails; a throw counts only when it carries the caught name as the thrown
  value/cause or through `addSuppressed`, so `throw IllegalStateException("x")` fails. Two planted
  cases added; the check's tests pass 9/9 (the existing 7 unchanged) and the clean production tree
  still passes.
  (3) `Main` no longer prints the false `Device probe: 0 GPU(s)` on a launch failure: with
  `deviceProbeError != null` it logs `Device probe failed: <message>; offering CPU and auto` and
  prints no count; a successful probe keeps the existing info line. No new test (the settings and
  e2e tests pin the reported failure).
  Verification: `node docs/check-exceptions.ts` clean; `node --test docs/check-exceptions.test.ts`
  9/9; `actionlint` clean; `check-links` and both architecture checks pass; `gradle check` exit 0
  (115 suites, 327 tests, 0 failures).

- 2026-10-18: user approved the diff; spec moved to `docs/specs/exception-handling-policy.md` with
  `Status: done`, squashed to one commit on top of `main` (ADR 0050). The e2e/CI `which whisper-cli`
  question is verified by the PR's E2E job on the squashed tree.
