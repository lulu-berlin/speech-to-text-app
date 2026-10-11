# 32 — Settings page: device, threads and upload defaults

Status: in-progress
Depends on: [11](../docs/specs/processing-engine.md) (done), [12](../docs/specs/http-api-and-sse.md) (done), [13](../docs/specs/frontend-foundation.md) (done), [21](21-frontend-backend-end-to-end.md) (the `e2e/` suite, ADR 0046)

## Goal

Let the user choose in the app whether transcription runs on the CPU or on a specific GPU, and keep that choice in the database so later runs use it — instead of setting `STT_DEVICE` before every start.

## Already decided

- [ADR 0046 — Every app feature has an end-to-end test](../docs/decisions/0046-every-feature-has-an-e2e-test.md): this item's test list includes at least one Playwright e2e test in `e2e/`, run by the user against the real backend.
- Today the device is configuration only: `STT_DEVICE` = `auto` (default) | `cpu` | `gpu` | a GPU index ≥ 0, parsed in `Config` into `Device` and turned into whisper-cli flags by `DeviceArgs` (none = first GPU, `--no-gpu` = CPU, `--device N` = GPU N). An invalid value stops startup.
- The speaker model (CAM++, ONNX Runtime) runs on the CPU only; GPU diarization was considered and not pursued in item 30 (CUDA-only ORT GPU build, ~0.4 s per run).
- Storage: SQLite with Flyway migrations ([ADR 0006](../docs/decisions/0006-exposed-flyway-kotest.md), [ADR 0019](../docs/decisions/0019-schema-ownership-and-evolution.md)); the API is contract-first with `docs/api/` examples ([ADR 0029](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md)); UI flows are machines ([ADR 0038](../docs/decisions/0038-ui-state-transitions-are-machines.md)).

## Proposal

To be settled in the consultation. Starting points:

- **Storage**: a `settings` table (new migration), one row per key; the device setting is `auto` | `cpu` | `gpu:<index>`.
- **API** (extends item 12's contract and `docs/api/`):
  - `GET /api/settings` → the current settings and the available devices;
  - `PUT /api/settings` (or per key) → validates and stores, `200` with the settings; an unknown device → `400`.
  - A `settings-updated` event on `/api/events`, so open tabs follow.
- **Available devices**: the backend lists them for the UI — CPU always, plus the GPUs whisper-cli can use (how to enumerate is an open question).
- **Applies to**: the next task the engine starts; a running task keeps its device.
- **UI**: a Settings view (new route, e.g. `/settings`, reached from the navigation) with a device select and a short explanation; saving shows confirmation or the error inline.

## Open questions (answered 2026-10-11)

- `STT_DEVICE`? → **Removed.** The device lives only in the database and is configured through the Settings page or the API; startup no longer reads `STT_DEVICE` (AGENTS.md and items 16/19 drop it).
- How are GPUs found? → **Ask whisper-cli at startup**: the backend runs whisper-cli's device probe once and parses the devices it reports; the Settings page offers CPU, auto and those devices.
- When does a change apply? → **The next task** the engine starts; a running task keeps its device.
- Scope? → **Device + threads + upload defaults**: the device, the thread count (today `STT_THREADS`, whisper-cli and ONNX intra-op), and the upload form's defaults — language, speakers mode (`auto` / `off` / a number) and max speakers.
- `STT_THREADS`? → **Removed** too: the thread count lives only in the settings (one source of truth); startup reads neither `STT_DEVICE` nor `STT_THREADS`.
- `maxSpeakers` default? → **A setting**: starts at 8, configurable on the Settings page, and the upload form starts with it.
- The CPU-fallback notice? → **A banner on the Queue**: an unavailable GPU is a global condition, not one task's; the banner names the device and says the setting was switched to CPU.
- Chosen GPU unavailable when a task runs? → **Fall back to the CPU with a notice**, and the stored device setting **is switched to CPU**, so using the GPU again requires opting back in on the Settings page.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] Settings (device, threads, default language, default speakers mode, default max speakers — 8 on a fresh database) are stored in the database, have defaults on a fresh database, and survive a restart
- [x] At startup the backend asks whisper-cli for its devices; `GET /api/settings` returns the stored settings and the available devices (CPU, auto, and each reported GPU) and matches its `docs/api/` example
- [ ] `PUT /api/settings` validates (an unknown device, threads < 1, a language not in `languages.json`, an invalid speakers mode, a max speakers outside 1–20 → 400 with the matching code) and stores; the response and a `settings-updated` event carry the new settings
- [ ] The engine uses the stored device and thread count for the next task's whisper-cli flags (and the thread count for the speaker model); a running task keeps its device
- [ ] Neither `STT_DEVICE` nor `STT_THREADS` is read any more; startup works without them and AGENTS.md no longer documents them
- [ ] A chosen GPU that is unavailable when a task runs: the task completes on the CPU, the stored device is now CPU, and the Queue shows a global banner naming the device and the switch (using the GPU again needs opting back in)
- [ ] The upload form starts with the stored default language, speakers mode and max speakers
- [ ] The Settings page (a new route, reached from the navigation) shows the current settings and the available devices, saves a change, shows the rejection inline, and follows a `settings-updated` from another tab
- [ ] (e2e, ADR 0046) Against the real backend: change the device and the defaults on the Settings page, restart the backend, the choices are kept; the upload form uses the new defaults; a task runs with the stored device
- [ ] (must not break) All suites and the contract-shape checks stay green
- [x] (discovered) A device probe that fails (whisper-cli missing, or reporting no GPU) yields no GPU: the app still starts and offers CPU and auto

## Acceptance criteria

- The user switches between CPU and a GPU in the app, restarts, and the choice is kept; transcription runs on the chosen device.
- Contract, examples and route tests agree.
- The e2e test passes on the user's machine (ADR 0046).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's request (a settings page to choose CPU vs which GPU, persisted in the database for future runs).
- 2026-10-11: consultation answers (STT_DEVICE removed; GPUs from whisper-cli at startup; next task only; scope device + threads + default language + default speakers mode; unavailable GPU → CPU with a notice and the setting switched to CPU) and the proposed test list incl. its e2e test (ADR 0046). Remaining: STT_THREADS, maxSpeakers default, the notice's form; test list approval.
- 2026-10-11: remaining answers: `STT_THREADS` removed; the max-speakers default is a setting (8 initially); the CPU-fallback notice is a global banner on the Queue. Remaining: test list approval.
- 2026-10-11: test list (10 items, incl. the e2e test) approved by the user; status `spec-approved`. Starts when item 21 delivers the `e2e/` suite.
- 2026-10-11 (workspace, cycle 1, "Settings … are stored in the database, have defaults on a fresh database, and survive a restart"): new `V6__settings.sql` — a single-row `settings` table (the `queue_state` pattern, seeded by the migration with the settled defaults: device `auto`, threads unset = whisper-cli/ONNX default, language `auto`, speakers `auto`, max speakers 8), not the proposal's "one row per key" (same codebase precedent, no JSON layer; the consultation left the row shape open). New `Settings` value, `SettingsTable` mapping and `SettingsRepository` (get/update), plus `Device.dbValue()`/`parseStoredDevice` for the stored `auto|cpu|gpu:<index>` form. Red: `compileTestKotlin` failed — unresolved `Settings`/`SettingsRepository`. Green: spec + `DatabaseSpec` (updated to V6: 6 migrations, `settings` in the table list) pass; `gradle test` green. No discoveries.
- 2026-10-11 (workspace, cycle 2, "At startup the backend asks whisper-cli for its devices; `GET /api/settings` …"): probe format recovered from whisper-cli 1.9.2 itself — libwhisper's `%s: found GPU device %zu: %s (type: %d, cnt: %d)` per GPU and `%s: no GPU found`, printed after the model load and before the audio is read; on this sandbox (no GPU) `whisper-cli -m <model> -f /dev/null` prints `whisper_backend_init_gpu: device 0: CPU (type: 0)` / `… no GPU found` and fails on the input in ~0.5 s. Contract first: new `docs/api/settings.json` (flat: `device`, `threads`, `defaultLanguage`, `speakersMode`, `speakersCount`, `maxSpeakers`, plus `availableDevices` on GET — `cpu`, `auto`, then `gpu:<index>` per reported GPU), its row in `docs/api/README.md`, and a Settings entry in `docs/specs/http-api-and-sse.md`'s contract. New `DeviceProbe` (`stt.transcribe`; runs `whisper-cli -m <model> -f /dev/null` through `ProcessRunner`, parses stderr, any failure → no GPU with a WARN), run once by `main()` before the server starts and passed to `sttApp(config, gpus)` (default empty, so no test spawns a probe); new `settingsRoutes` (`GET /api/settings`) and `SettingsRepository` in DI. Tests: `SettingsRoutesSpec` (unit; GPUs 0/1 → the body equals `settings.json`; no GPU → `["cpu","auto"]`) and `DeviceProbeSpec` (Integration, `fakeWhisperCli` stand-ins exiting 1: two GPUs parsed in order; CPU-only output → none; missing executable → none). Red: compile failures — unresolved `GpuDevice`/`DeviceProbe`, no `gpus` parameter on `sttApp`; on the way, the probe's first stand-in used a `/bin/sh` shebang, absent in this sandbox (exit 127, nothing parsed), so the spec moved to the existing `fakeWhisperCli` (`sh` from PATH). Green: both specs; smoke `gradle run` against the real whisper-cli/model: `Device probe: 0 GPU(s) []` at boot, `GET /api/settings` → 200 with the defaults and `["cpu","auto"]`. Also: cycle 1's files were not ktlint-clean (verified with `gradle test`, not `gradle check`); fixed in a separate format-only commit. Discovered: the failing-probe scenario above (to confirm). Knock-on for items 16/19 (note only): the server now runs whisper-cli once at startup, so the packaged app needs whisper-cli on its PATH before the first task, too.
