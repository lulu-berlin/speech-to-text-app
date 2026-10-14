# 45 — Make both GPUs selectable: enumerate physical Vulkan devices

Status: in-review
Depends on: [11](../docs/specs/processing-engine.md) (done), [32](../docs/specs/settings-page.md) (done; the device setting and the probe)
Blocks: —

## Goal

The Settings page lists every Vulkan GPU whisper-cli can actually be directed to, and choosing one uses it for the task. Today the page offers one "GPU" even on a machine with two GPUs, because the probe counts libwhisper's `found GPU device` lines — and libwhisper stops printing them at the device it selects.

## Already decided

- Found 2026-10-12 on the user's machine (AMD Rembrandt iGPU + NVIDIA RTX 3050, both visible to Vulkan). Diagnostics: `vulkaninfo` sees all three ICDs; whisper-cli's backend prints `ggml_vulkan: Found 2 Vulkan devices: 0 = AMD … (RADV REMBRANDT) …, 1 = NVIDIA GeForce RTX 3050 Laptop GPU (NVIDIA) …`; with the default `--device 0`, `whisper_backend_init_gpu` prints only `found GPU device 0: Vulkan0 (type: 2, cnt: 0)`.
- Corrected 2026-10-14 (whisper.cpp 1.8.4 source, confirmed on the user's machine): ggml-vulkan registers **one ggml device per enumerated GPU** — `Vulkan<i>` is entry `i` of the list the `ggml_vulkan: i = …` lines print (`ggml_backend_vk_reg_get_device_count` = `vk_instance.device_indices.size()`). `whisper_backend_init_gpu` walks the GPU devices and **breaks once it reaches `--device N`**, so the single `found GPU device` line was an artifact of `--device 0`, not a single device. On the user's machine `--device 1` prints `devices = 3`, `found GPU device 0: Vulkan0 (type: 2, cnt: 0)`, `found GPU device 1: Vulkan1 (type: 1, cnt: 1)`, `using Vulkan1 backend`, and loads the model onto `Vulkan1` (the NVIDIA).
- Therefore today's `DeviceArgs.kt` (`--device N`) already selects physical GPU N; the gap was only the probe, which listed one GPU. `GGML_VK_VISIBLE_DEVICES` is **not** used: it indexes Vulkan's raw physical-device list (CPU-type devices such as llvmpipe included, so its indices can differ from ggml's) and throws `Invalid Vulkan device index` on an out-of-range index instead of reaching libwhisper's `no GPU found` fallback.
- Item 32's settled contract ("the GPUs whisper-cli reports, from a startup probe") is the thing this item extends; the probe stays whisper-faithful, but what it reports becomes the **physical** device list.

## Proposal

To be settled in the consultation. Starting point:

- **Enumeration, no extra probe runs**: the existing startup probe already makes whisper-cli print every enumerated GPU — `ggml_vulkan: N = <name> (<driver>) | uma: … | matrix cores: …` (verified on the user's machine). Parse those lines; libwhisper's `found GPU device` lines are not the device list.
- **Per-task selection** (amended 2026-10-14): unchanged from item 32 — `gpu:N` → `--device N` → `Vulkan<N>`, the GPU the probe listed as N; `auto` adds no flag, `cpu` adds `--no-gpu`. No environment wrapper.
- **Wire form** (amended 2026-10-14): `availableDevices` keeps the index strings; a new additive field `gpuNames` maps each offered `gpu:<index>` to the probe's name (e.g. `{"gpu:0": "AMD Ryzen 9 6900HS Creator Edition (RADV REMBRANDT)", "gpu:1": "NVIDIA GeForce RTX 3050 Laptop GPU"}`; `{}` without a GPU) — a `docs/api/settings*.json` contract change. Like `availableDevices` it is fixed by the startup probe, so it is not in `settings-updated`. The Settings page labels each GPU with its name.
- **Fallback unchanged**: a chosen GPU that cannot initialize at run time → CPU with the stored notice (item 32).
- The concurrency, delete, pause, diarization paths are untouched; the setting applies to the next task as before.

## Open questions (answered 2026-10-12)

- `availableDevices` on the wire? → **Keep the index strings** (`cpu`, `auto`, `gpu:<index>`) — they enable the full range of choices; the Settings page maps the names (user). Amended 2026-10-14: the names come from a new additive `gpuNames` field (user), since the page has no other source for them.
- The physical list's order? → **Not a decision**: the indices are defined by the order the probe prints (ggml's enumeration on that boot), so `gpu:N` always means "the Nth device the probe listed"; the page shows the same list. (The question was noise; removed.)
- Per-run env plumbing? → ~~A per-call `env GGML_VK_VISIBLE_DEVICES=<index>` wrapper~~ **Withdrawn 2026-10-14**: it rested on the "single ggml device" misreading; `--device N` selects `Vulkan<N>` directly (see Already decided).

## Test list (proposed; behaviors only)

- [x] The probe lists every physical Vulkan GPU whisper-cli can see, with its name (user's machine: AMD and NVIDIA; a GPU-less machine: none — cpu and auto only)
- [x] Choosing `gpu:N` runs that task's whisper-cli on `Vulkan<N>`, the GPU the probe listed as N: the running whisper-cli has `--device N` and runs on that GPU (`ps`, `nvidia-smi`, amdgpu `gpu_busy_percent`; user-verified on the user's machine for both GPUs). (discovered 2026-10-14: the app logs no whisper-cli output on success, so the run's log cannot show the backend; user chose the process/GPU observation over a new log line)
- [x] `auto` adds no device flag (the backend's default selection); `cpu` passes `--no-gpu` (already pinned by `DeviceArgsSpec`, green; no new test — nothing to turn red; ticked with the user's approval 2026-10-14)
- [x] A stored `gpu:<index>` past the probe's GPU count still falls back to the CPU with the notice (libwhisper's `no GPU found`; item 32's rule)
- [x] `GET /api/settings` names each offered GPU: `gpuNames` maps `gpu:<index>` to the probe's name (the `settings*.json` examples; the `200` of the settings writes shares the shape)
- [x] Without a GPU, `gpuNames` is `{}`
- [x] The Settings page shows each offered GPU by its name (from `gpuNames`) and saving it applies to the next task (existing settings behaviors unchanged)
- [x] (must not break) The suites, the contract-shape checks (`settings*.json`) and the e2e journey stay green

## Acceptance criteria

- On a two-GPU machine, the Settings page offers both GPUs by name and each actually transcribes on the chosen one; on a GPU-less machine nothing changes.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the user's two-GPU diagnosis (whisper-cli exposes a single ggml device; `GGML_VK_VISIBLE_DEVICES` selects the physical GPU; the probe and `--device` index were the gap).
- 2026-10-12: consultation answers: index-string `availableDevices` kept; per-run env via a per-call `env` wrapper (runner untouched); the order question withdrawn (indices are the probe's printed order by definition). Test list approved; status `spec-approved`.
- 2026-10-14: amended in the workspace after cycle 1 (user-approved): the "single ggml device" diagnosis was wrong — libwhisper stops printing `found GPU device` at the selected device; ggml-vulkan registers `Vulkan0`/`Vulkan1`, and `--device 1` selects the NVIDIA (user's run). The `GGML_VK_VISIBLE_DEVICES` wrapper is withdrawn; per-task selection stays `--device N`; test-list items 2–4 reworded accordingly. Item 5's name source (names are not on the wire) to be settled when item 5 is next.
- 2026-10-14: user answers: item 2 verified by observing the running process and GPU (no new log line); item 3 ticked on the existing `DeviceArgsSpec`; item 5's names come from a new additive `gpuNames` field on `GET /api/settings` (contract change).
- 2026-10-14: item 5 split into the wire field (with GPUs, without) and the page (user-approved).
- 2026-10-14: 5b pinned: `SettingsRoutesSpec`'s existing no-GPU scenario now asserts `gpuNames == {}` (green on first run — the probe reporting no GPU already yields `{}`, so no red). Known-issues backlog: frontend `vitest` CPU-contention timeouts (LOW). 5c: the fallback banner shows the probe name too (user).
- 2026-10-14: 5c done. `deviceLabel` (shared, item 45) labels an offered GPU `GPU <n> — <name>` from `gpuNames`, and no longer duplicated; the Queue's CPU-fallback banner names the unavailable GPU too (name from the fetched settings; bare `GPU <n>` when unnamed). `Settings` gained `gpuNames` (excluded from `SettingsUpdate`/`SettingsNotice`); the machine preserves it across `settings-updated`/rejected writes. E2E 17-settings extended (regex + a `gpuNames` join when GPUs are offered); ticked now, the two-GPU join confirmed by the user's final run.
- 2026-10-14: (must not break) verified in the workspace: backend `gradle check integrationTest` green (one `AudioJoinIntegrationSpec` flake in the parallel run, green on rerun, filed LOW), frontend 173/173 (tsc + prettier), the contract-shape checks via the settings specs, and the full e2e journey 21 passed. Items 2 and 4 await the user's two-GPU run before `in-review`.
- 2026-10-14: item 2 verified on the user's two-GPU machine. `gpu:1` (NVIDIA): whisper-cli ran with `--device 1` and `nvidia-smi` listed it (893 MiB). `gpu:0` (AMD): `--device 0`, `nvidia-smi` did not list it, amdgpu `gpu_busy_percent` 100. `GET /api/settings` on hardware carried both real names (`gpuNames`), re-confirming 5a. Still open: item 4 (the `gpu:2` fallback run) and the 5c two-GPU e2e — the latter failed only because the host's `frontend/dist` was a pre-5c build (page showed `GPU N` without names), so it must be rebuilt first.
- 2026-10-14: item 4 verified on the two-GPU machine: with `gpu:2` stored, the 60 s clip completed on the CPU (a real ~70 s transcription) and `GET /api/settings` answered `{"device":"cpu","deviceFallback":"gpu:2"}`.
- 2026-10-14: 5c's e2e join verified on the two-GPU machine — after rebuilding `frontend/dist` (the first run had served a stale pre-5c bundle), `17-settings.spec.ts` passed with `Device probe: 2 GPU(s)`, so each option was `GPU <n> — <name>` against the real probe names.
- 2026-10-14: test list complete (all items green); `Status: in-review`. HANDOFF.md deleted. The full diff is for the user to review.

- 2026-10-14 (root agent, review + PR preparation): no blocking finding. Two fixes on the branch
  before the squash. **F1**: the device line's index is now read with `toIntOrNull()` and an
  unreadable line is ignored — before, `toInt()` threw `NumberFormatException` out of `gpus()` into
  `Main.main`, aborting startup for a garbled or future-format line; new unit spec
  `VulkanDeviceLinesSpec` (red on the old parser) pins the tolerance. **F2** (merge order, recorded
  here, no code change): the `AudioJoinIntegrationSpec` row this branch filed in the known-issues
  backlog stays valid while item 44 is unmerged — that row is exactly what item 44's merge deletes
  (it removes the joiner). If item 44 lands first, this branch's rebase drops the row and re-runs
  `gradle check integrationTest`; if this branch lands first, item 44's rebase does the same from
  its side. The branch is squashed to one commit on top of `main` (ADR 0050) for the PR.
