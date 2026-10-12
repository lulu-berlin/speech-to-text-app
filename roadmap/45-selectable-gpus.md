# 45 — Make both GPUs selectable: enumerate physical Vulkan devices

Status: spec-approved
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
- **Per-task selection**: when a task's device is a specific GPU, that task's whisper-cli command is wrapped as `env GGML_VK_VISIBLE_DEVICES=<index> whisper-cli … --device 0` (the one ggml device is then that GPU); `auto`/`cpu` set nothing / `--no-gpu` as today. The wrapper keeps `ProcessRunner` and the audio joiner's runner untouched (user, 2026-10-12).
- **Wire form**: `gpu:<index>` still indexes the physical list (the order ggml prints), and the Settings page's labels use the names/enumerate (e.g. "GPU 0 — NVIDIA GeForce RTX 3050"; open question whether `availableDevices` gains names on the wire, which is a `docs/api` contract change).
- **Fallback unchanged**: a chosen GPU that cannot initialize at run time → CPU with the stored notice (item 32).
- The concurrency, delete, pause, diarization paths are untouched; the setting applies to the next task as before.

## Open questions (answered 2026-10-12)

- `availableDevices` on the wire? → **Keep the index strings** (`cpu`, `auto`, `gpu:<index>`) — they enable the full range of choices; the Settings page maps the names (user).
- The physical list's order? → **Not a decision**: the indices are defined by the order the probe prints (ggml's enumeration on that boot), so `gpu:N` always means "the Nth device the probe listed"; the page shows the same list. (The question was noise; removed.)
- Per-run env plumbing? → **A per-call wrapper**: the whisper command is built as `env GGML_VK_VISIBLE_DEVICES=<index> whisper-cli …` — coreutils' `env` is on PATH in every environment (the devenv, the closure's `--prefix PATH`, item 16), so `ProcessRunner` and the joiner's runner stay untouched (user).

## Test list (proposed; behaviors only)

- [ ] The probe lists every physical Vulkan GPU whisper-cli can see, with its name (user's machine: AMD and NVIDIA; a GPU-less machine: none — cpu and auto only)
- [ ] Choosing a specific GPU runs that task's whisper-cli against it: the run's process env carries `GGML_VK_VISIBLE_DEVICES=<index>` and the run's log shows that physical device (user-verified on the user's machine for both GPUs)
- [ ] `auto`/`cpu` set no device env (auto keeps the backend's selection; cpu passes `--no-gpu`)
- [ ] A stored `gpu:<index>` past the physical count still falls back to the CPU with the notice (item 32's rule)
- [ ] The Settings page shows each offered GPU (index strings; the names mapped in the UI) and saving it applies to the next task (existing settings behaviors unchanged)
- [ ] (must not break) The suites, the contract-shape checks (`settings*.json`) and the e2e journey stay green

## Acceptance criteria

- On a two-GPU machine, the Settings page offers both GPUs by name and each actually transcribes on the chosen one; on a GPU-less machine nothing changes.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the user's two-GPU diagnosis (whisper-cli exposes a single ggml device; `GGML_VK_VISIBLE_DEVICES` selects the physical GPU; the probe and `--device` index were the gap).
- 2026-10-12: consultation answers: index-string `availableDevices` kept; per-run env via a per-call `env` wrapper (runner untouched); the order question withdrawn (indices are the probe's printed order by definition). Test list approved; status `spec-approved`.
