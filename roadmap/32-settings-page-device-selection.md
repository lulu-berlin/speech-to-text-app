# 32 — Settings page: device, threads and upload defaults

Status: draft
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
- Scope? → **Device + threads + upload defaults**: the device, the thread count (today `STT_THREADS`, whisper-cli and ONNX intra-op), and the upload form's defaults — language and speakers mode (`auto` / `off` / a number).
- Chosen GPU unavailable when a task runs? → **Fall back to the CPU with a notice**, and the stored device setting **is switched to CPU**, so using the GPU again requires opting back in on the Settings page.

## Remaining open questions

- `STT_THREADS`: removed like `STT_DEVICE` (the setting is the only source), or kept as an environment override? Proposed: removed, for one source of truth.
- `maxSpeakers` default: also a setting next to the speakers mode, or stays 8?
- How is the CPU-fallback notice shown: a banner on the Queue for that task, an entry on the task (its `error`-like field), or both?

## Test list (written with the user; Canon TDD step 1)

Proposed 2026-10-11, to be approved. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] Settings (device, threads, default language, default speakers mode) are stored in the database, have defaults on a fresh database, and survive a restart
- [ ] At startup the backend asks whisper-cli for its devices; `GET /api/settings` returns the stored settings and the available devices (CPU, auto, and each reported GPU) and matches its `docs/api/` example
- [ ] `PUT /api/settings` validates (an unknown device, threads < 1, a language not in `languages.json`, an invalid speakers mode → 400 with the matching code) and stores; the response and a `settings-updated` event carry the new settings
- [ ] The engine uses the stored device and thread count for the next task's whisper-cli flags (and the thread count for the speaker model); a running task keeps its device
- [ ] `STT_DEVICE` (and, if decided, `STT_THREADS`) is no longer read; startup works without it
- [ ] A chosen GPU that is unavailable when a task runs: the task completes on the CPU, the user sees a notice, and the stored device is now CPU
- [ ] The upload form starts with the stored default language and speakers mode
- [ ] The Settings page (a new route, reached from the navigation) shows the current settings and the available devices, saves a change, shows the rejection inline, and follows a `settings-updated` from another tab
- [ ] (e2e, ADR 0046) Against the real backend: change the device and the defaults on the Settings page, restart the backend, the choices are kept; the upload form uses the new defaults; a task runs with the stored device
- [ ] (must not break) All suites and the contract-shape checks stay green

## Acceptance criteria

- The user switches between CPU and a GPU in the app, restarts, and the choice is kept; transcription runs on the chosen device.
- Contract, examples and route tests agree.
- The e2e test passes on the user's machine (ADR 0046).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's request (a settings page to choose CPU vs which GPU, persisted in the database for future runs).
- 2026-10-11: consultation answers (STT_DEVICE removed; GPUs from whisper-cli at startup; next task only; scope device + threads + default language + default speakers mode; unavailable GPU → CPU with a notice and the setting switched to CPU) and the proposed test list incl. its e2e test (ADR 0046). Remaining: STT_THREADS, maxSpeakers default, the notice's form; test list approval.
