# 60 — Exception-handling policy: no catch-all swallows a throwable silently

Status: spec-approved
Depends on: [42](../docs/specs/worker-stability.md) (done; this item starts after 42 lands, because
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

- `engine/ProcessingEngine.kt:501` (`catch (e: Exception)` in the task-failure path): the throwable
  is persisted as `e.message ?: e.toString()` and **never logged**; when `failIfRunning` loses the
  race to a cancel, nothing is recorded or logged at all. Already a MED row in `docs/known-issues.md`
  (`ProcessingEngine.kt:501-506`). Item 42 is reworking this exact region right now (its loop guard
  around `runTask`); this item starts after 42 lands (see Decided).
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
  control flow for a bad stored value, but `runCatching` discards *every* `Throwable` — a bug in the
  parser would be indistinguishable from bad input. Narrowing candidate.

Related shape checked and fine: the engine's phase guard rethrows `CancellationException` before its
`catch (e: Exception)` (`ProcessingEngine.kt:497-501`); the item-42 guard adds the same split for
`Throwable`. No `runCatching` in production code wraps suspending calls (the two uses above are pure
functions).

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

- [ ] A task that fails mid-run logs its throwable (full stack) with the task's MDC context, in
      addition to the persisted record
- [ ] When the failure write itself loses the race to a cancel, the loss is logged (task id, cause)
- [ ] A device probe whose process cannot be launched (an `IOException`) is reported as a failed
      probe — the settings JSON carries the failure and its message, and the Settings page shows it
      — while the app still starts and offers CPU/auto; a probe that succeeds with zero devices
      shows no failure (the true "no GPU")
- [ ] Any other `Throwable` from the probe propagates and fails startup
- [ ] (e2e, ADR 0046) Against the real backend launched without a usable whisper-cli, the Settings
      page shows the probe-failure notice; with whisper-cli present, it shows none
- [ ] A failed scratch-directory cleanup logs the throwable
- [ ] A bad stored device value is still rejected as `invalid-device`, via the narrowed type rather
      than a catch-all
- [ ] The static check fails when a broad catch that neither rethrows nor logs the throwable (or a
      `runCatching`) is planted in production code, passes on the clean tree, and is wired into CI
      and AGENTS.md § Commands
- [ ] (must not break) All other audited behaviors stay: the runtime CPU-fallback banner, best-effort
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
