# 45 — Make both GPUs selectable: enumerate physical Vulkan devices

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done), [32](../docs/specs/settings-page.md) (done; the device setting and the probe)
Blocks: —

## Goal

The Settings page lists every Vulkan GPU whisper-cli can actually be directed to, and choosing one uses it for the task. Today the page offers one "GPU" even on a machine with two GPUs, because the probe mirrors whisper-cli's device list — which is a single ggml device.

## Already decided

- Found 2026-10-12 on the user's machine (AMD Rembrandt iGPU + NVIDIA RTX 3050, both visible to Vulkan). Diagnostics: `vulkaninfo` sees all three ICDs; whisper-cli's backend prints `ggml_vulkan: Found 2 Vulkan devices: 0 = AMD … (RADV REMBRANDT) …, 1 = NVIDIA GeForce RTX 3050 Laptop GPU (NVIDIA) …`; but `whisper_backend_init_gpu` iterates **one** ggml device (`device 0: Vulkan0 (type: 2)`) — whisper.cpp 1.8.4's Vulkan backend registers a single selectable ggml device ("Vulkan0"), and which physical GPU backs it is chosen at the environment level with `GGML_VK_VISIBLE_DEVICES`, not by whisper's `--device` and not per task.
- Therefore today (`DeviceArgs.kt` sends `--device N`): `auto` runs the backend's default selection; `gpu:1` on the user's machine would find no ggml device 1 → "no GPU found" → CPU fallback with the notice — legal but not the intent.
- Item 32's settled contract ("the GPUs whisper-cli reports, from a startup probe") is the thing this item extends; the probe stays whisper-faithful, but what it reports becomes the **physical** device list.

## Proposal

To be settled in the consultation. Starting point:

- **Enumeration, no extra probe runs**: the existing startup probe already makes whisper-cli print every visible physical device — `ggml_vulkan: N = <name> (<backend>) | uma: … | matrix cores: …` — because `GGML_VK_VISIBLE_DEVICES` unset exposes them all (verified on the user's machine). Parse those lines (the `found GPU device` lines stay as the effective-device check).
- **Per-task selection**: when a task's device is a specific GPU, its whisper-cli runs with `GGML_VK_VISIBLE_DEVICES=<index>` (and `--device 0`, since the one ggml device is then that GPU); `auto`/`cpu` set nothing / `--no-gpu` as today. The engine/runner gains a per-run environment (today `ProcessRunner` inherits the JVM env; a small per-run env parameter, kept engine-only — the audio joiner's runner is untouched).
- **Wire form**: `gpu:<index>` still indexes the physical list (the order ggml prints), and the Settings page's labels use the names/enumerate (e.g. "GPU 0 — NVIDIA GeForce RTX 3050"; open question whether `availableDevices` gains names on the wire, which is a `docs/api` contract change).
- **Fallback unchanged**: a chosen GPU that cannot initialize at run time → CPU with the stored notice (item 32).
- The concurrency, delete, pause, diarization paths are untouched; the setting applies to the next task as before.

## Open questions (to be answered in the consultation)

- `availableDevices` on the wire: keep `["cpu","auto","gpu:0","gpu:1"]` indices (Settings page shows names by mapping) or enrich to value+label objects (a contract change with new `docs/api/settings*.json` variants)?
- The physical list's order/stability: ggml's enumeration order (as today) or sorted by name?
- Per-run env plumbing: an explicit `env` parameter on `ProcessRunner.run` (recommended) vs a per-call wrapper — and whether the probe's starting run also sets nothing (all devices visible) as today.

## Test list (proposed; behaviors only)

- [ ] The probe lists every physical Vulkan GPU whisper-cli can see, with its name (user's machine: AMD and NVIDIA; a GPU-less machine: none — cpu and auto only)
- [ ] Choosing a specific GPU runs that task's whisper-cli against it: the run's process env carries `GGML_VK_VISIBLE_DEVICES=<index>` and the run's log shows that physical device (user-verified on the user's machine for both GPUs)
- [ ] `auto`/`cpu` set no device env (auto keeps the backend's selection; cpu passes `--no-gpu`)
- [ ] A stored `gpu:<index>` past the physical count still falls back to the CPU with the notice (item 32's rule)
- [ ] The Settings page shows each offered GPU with its name (or the agreed wire form) and saving it applies to the next task (existing settings behaviors unchanged)
- [ ] (must not break) The suites, the contract-shape checks (`settings*.json`) and the e2e journey stay green

## Acceptance criteria

- On a two-GPU machine, the Settings page offers both GPUs by name and each actually transcribes on the chosen one; on a GPU-less machine nothing changes.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the user's two-GPU diagnosis (whisper-cli exposes a single ggml device; `GGML_VK_VISIBLE_DEVICES` selects the physical GPU; the probe and `--device` index were the gap).