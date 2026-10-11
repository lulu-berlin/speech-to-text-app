# 32 — Settings page: processing device selection

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

## Open questions

- Precedence between the stored setting and `STT_DEVICE`: does the environment variable still override (useful for the Docker/Nix deployments, items 16 and 19), act only as the initial default, or go away?
- How to enumerate the GPUs: parse whisper-cli's device listing at startup, query the Vulkan devices, or let the user enter an index?
- When the device changes: the next task only, or also a running one (restart its current chunk)?
- Which other settings belong on the page now — `STT_THREADS`, the default upload language and speaker options — or only the device in this item?
- Does a failing device (e.g. GPU chosen but unavailable) fall back to CPU with a notice, or fail the task with a readable error?
- Order with items 16/19: the Nix and Docker deployments set the device through the environment; does this item come before them so their configs document the new behavior?

## Test list (written with the user; Canon TDD step 1)

To be written with the user. Expected scope:

- The device setting is stored in the database and survives a restart; the engine uses it for the next task's whisper-cli flags.
- `GET /api/settings` lists the stored setting and the available devices; `PUT` validates (unknown device → 400) and stores; both match their `docs/api/` examples.
- The decided precedence between `STT_DEVICE` and the stored setting holds.
- A settings change is announced on `/api/events`.
- The Settings view shows the current device, saves a new choice, and shows an error when the save is rejected.
- A running task keeps its device (or the decided behavior).

## Acceptance criteria

- The user switches between CPU and a GPU in the app, restarts, and the choice is kept; transcription runs on the chosen device.
- Contract, examples and route tests agree.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's request (a settings page to choose CPU vs which GPU, persisted in the database for future runs).
