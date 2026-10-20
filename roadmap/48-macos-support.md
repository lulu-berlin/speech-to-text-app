# 48 — macOS support

Status: in-progress
Depends on: [05](../docs/specs/process-runner.md) (done), [16](../docs/specs/nix-flake-package.md) (done), [45](../docs/specs/device-selection.md) (done; macOS GPU surface must not conflict), [51](../docs/specs/ci-verification.md) (done; the macOS CI runners that verify this item), [50](../docs/specs/electron-desktop-app.md) (done; the Linux desktop app and its packaged specs, which the macOS packaged-app item extends)
Blocks: — [49](49-windows-support.md)

## Goal

The app runs, transcribes, diarizes, pauses and uses its GPU on macOS — Apple Silicon and Intel —
through the same paths the Linux app has: the Nix flake package, the devenv development environment,
and the Gradle bootstrap. Process control is correct (the Linux signal numbers that swap pause and
resume on macOS are gone), Metal acceleration works, and the e2e journey is green. README no longer
needs the manual `brew install util-linux` workaround. Verification happens on CI macOS runners
(nobody on the team owns a Mac).

## Evidence

Filed 2026-10-13 from a portability pass over `main` (verified reads, nothing executed):

- **Pause/resume are swapped on macOS** — `ProcessTree.kt:75-79` hardcodes Linux numbers
  SIGSTOP=19/SIGCONT=18; macOS/BSD is SIGSTOP=17/SIGCONT=19, so `pause()` sends SIGCONT (resumes)
  and `resume()` sends SIGTSTP/SIGSTOP (stops). A genuine bug.
- **No `setsid` on macOS** — `ProcessRunner.kt:73` prepends `setsid` to every command; macOS has no
  setsid binary. README.md:130-135 documents a manual `brew install util-linux` + PATH workaround.
- **GPU discovery is Vulkan-only** — `DeviceProbe.kt:44-77` parses whisper-cli's `found GPU device`
  line, which only the Vulkan backend prints; the darwin whisper-cli prebuild is CoreML
  (`nix/versions.nix`), so Settings shows only cpu/auto even where Metal exists.
- **The flake is hardcoded to x86_64-linux** — `flake.nix` fixes `system = "x86_64-linux"` for
  packages/apps/checks; `nix build .` on a Mac resolves nothing.
- **The nix build is Vulkan-shaped** — `nix/whisper-cpp.nix` overrides `vulkanSupport = true`, no
  Metal.
- **devenv lists Linux-only packages** — `devenv.nix` pulls `pkgs.util-linux` (setsid) and
  `pkgs.vulkan-tools`.
- **The e2e harness reads /proc** — `e2e/lib/backend.mjs:178-227` finds the JVM via `/proc/../cmdline`
  and `/proc/../stat`; macOS has no /proc, so e2e stop/cleanup would fail and leave the daemon.
- **Portable already** — `versions.json` carries darwin-arm64/x64 whisper-cli (CoreML) and ffmpeg
  artifacts; sqlite-jdbc and onnxruntime use per-OS bundled natives; data paths are `Path.of`-based.
- **CI exists now**: item 51's workflow runs the Linux stack on every push and pull request; the macOS runners this item adds extend it ([51](../docs/specs/ci-verification.md)).

## Decided (consultation, 2026-10-13)

- **Both architectures**: aarch64-darwin and x86_64-darwin (both artifact sets already exist in
  versions.json).
- **GPU support is in scope**: Metal/CoreML acceleration (whisper-cpp Metal build on darwin) and a
  Metal device probe; the Settings surface follows item 45's enumeration contract.
- **setsid on macOS**: direct-child kill fallback — whisper-cli and ffmpeg do not fork, so the
  tree-kill design degrades to direct-child kill on darwin (documented residual orphan risk if a
  spawned tool ever forks). Linux keeps process groups.
- **devenv on macOS is in scope**: `devenv up` and the dev shell work on darwin, with Linux-only
  packages (`setsid` util-linux, vulkan-tools) gated by system.
- **CI is the verification path** (no local Mac): macOS runners run the unit, Integration and
  e2e suites plus the Nix build per [51](../docs/specs/ci-verification.md).
- **DeviceArgs**: verify `--no-gpu`/`--device N` against the actually-pinned whisper-cli build as
  part of the GPU work (the `DeviceArgs.kt` 1.9.x-vs-1.8.4 mismatch row leaves the known-issues
  backlog for this item).

### Decided (checkpoint 1, 2026-10-19)

- **Metal device surface** (open question 1 answered): faithful to the probe. whisper-cli 1.8.4's
  darwin Metal log is `device N: MTL0 (type: 2)` / `GPU name: MTL0`; the human device name is not
  logged. A Metal machine offers `gpu:0` with `gpuNames` `{"gpu:0": "Metal (MTL0)"}` (page: `GPU 0 —
  Metal (MTL0)`); `gpu:0` → `--device 0`, `cpu` → `--no-gpu`, `auto` → no flag, per item 45.
- **Which binary has Metal**: the pinned darwin prebuilds have no ggml GPU backend — arm64 ships
  `libwhisper.coreml.dylib` (encoder-only) but no `libggml-metal.dylib`, x64 has neither — so the
  Gradle/bootstrap path honestly reports cpu/auto only. Metal is the Nix-built whisper-cpp:
  `nix/whisper-cpp.nix` becomes Vulkan on Linux and Metal on darwin (`metalSupport = true` also on
  x86_64-darwin, where nixpkgs defaults it off). The electron-builder macOS artifact stages the
  prebuild, so Metal in a packaged desktop app needs the flake `.#desktop` darwin build or a
  Metal-capable staged whisper-cli; the packaged-app item settles it rather than assumes it.
- **Metal verification**: the positive half is pinned by the 1.8.4 darwin lines in a unit test and
  user-verified on a real Mac (nobody on the team owns one); CI verifies the no-Metal half. GitHub
  macOS runners are VMs; GPU passthrough was unavailable in 2020 and is unconfirmed since.
- **CI legs** (open question 2 answered): both architectures, full tree — `macos-15` (ARM) and
  `macos-15-intel` (the last x86_64 image, through August 2027; `macos-13` is retired) — nightly and
  on this item's PRs.
- **Tick rule**: macOS-CI items tick only on a reported real run (the user's CI or a real Mac),
  never on a local proxy.

## Proposal

To be settled at the first test-list item, starting from the decisions above:

1. Process control: per-platform signal constants (Linux vs Darwin, by name or platform constants in
   LibC) — fixes the pause/resume swap; direct-child kill on darwin where setsid is absent; the e2e
   `/proc` lookup becomes a portable `ps`-based lookup.
2. Nix: declare `aarch64-darwin` and `x86_64-darwin` systems in the flake; enable the whisper-cpp
   Metal build on darwin; gate Linux-only devenv packages by system; give the packaged app a
   macOS-idiomatic data-dir default (`~/Library/Application Support`).
3. GPU: Metal device probing so a Metal machine reports its device(s) per item 45's contract
   (CoreML stays an encoder path, not a ggml device); cpu/auto always present; never crash on
   non-Vulkan machines.
4. Docs/tests: README mac section (workaround gone, Metal documented); macOS CI runs of the suites
   and a smoke transcription.

## Open questions (answered 2026-10-19)

- The Metal device surface? → **Faithful to the probe**: `gpu:0` with `gpuNames` `{"gpu:0":
  "Metal (MTL0)"}` on a Metal machine; `gpu:0` → `--device 0`, `cpu` → `--no-gpu`, `auto` → no flag
  (item 45's contract).
- Does the Intel runner stay? → **Yes, both architectures, full tree**: `macos-15` and
  `macos-15-intel`, nightly and on this item's PRs.

## Test list (approved 2026-10-19; behaviors only)

(Each macOS-CI item ticks only on a reported real run — the user's CI or a real Mac — never on a local proxy.)

- [ ] (CI, macOS ARM) `gradle check` + `gradle integrationTest` are green on a macOS runner
- [ ] (CI, macOS) Pausing a running task actually pauses its process and resume continues it: the
      process tree sends the platform's numbers (darwin SIGSTOP 17/SIGCONT 19; Linux 19/18), pinned
      host-independently and run through the real process runner on the macOS leg; ticks only on that run
- [ ] (CI, macOS) A full upload → transcribe → diarize run completes on macOS for a fixed fixture and
      its output matches the Linux run
- [ ] (CI, macOS) Cancel and delete stop the whole run (no orphan whisper-cli/ffmpeg processes)
- [ ] (CI, macOS + user) On a Metal-capable build (the Nix package on darwin), Settings shows
      `gpu:0` named `Metal (MTL0)` per item 45; on the pinned prebuild (no ggml GPU backend) and a
      machine without Metal, cpu/auto only and no crash or phantom GPU. The positive half is pinned
      by the recorded 1.8.4 darwin lines in a unit test and user-verified on a real Mac; the
      cpu/auto half runs on CI
- [ ] (CI, macOS) DeviceArgs flags verified against the bundled whisper-cli version (the shipped
      binary, not 1.9.x assumptions)
- [ ] (CI, macOS) The e2e journey (queue, history, settings, reorder, pause/cancel) is green on macOS
- [ ] (CI or user-verified) `nix build`/`nix run` and the devenv shell work on darwin
- [ ] (CI, inherited from 51 on 2026-10-14) This item adds its macOS legs — `macos-15` (ARM) and
      `macos-15-intel` — to `.github/workflows/ci.yml`; they run the full tree on the nightly
      schedule and on this item's PRs, and include the darwin Nix build above
- [ ] (e2e, CI macOS, moved from item 50 on 2026-10-15) The packaged desktop app runs on macOS with
      its per-user paths: item 50's packaged specs (journey, quit without orphan processes, first
      run with the model download and its persistence, download failure with Retry) pass against
      the installed macOS build
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build` and the
      Linux e2e stay green

## Acceptance criteria

- macOS (both architectures) is a documented, CI-tested platform: the README brew/setsid workaround
  is gone, the flake builds on darwin, pause/resume behave correctly, Metal works, and the e2e
  journey passes. GPU surface honors item 45's contract.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed from the macOS portability pass (signal swap, setsid, Vulkan-only probe, flake
  system, /proc e2e).
- 2026-10-13: consultation — both architectures; GPU (Metal) in scope; direct-child kill on darwin;
  devenv on macOS in scope; CI is the verification path (added dependency 51); DeviceArgs mismatch
  absorbed to be verified. Status: spec-approved.
- 2026-10-15: item 50's test item "The packaged app runs on macOS and Windows with their per-user
  paths" moved here (macOS half) by the user's decision, so item 50 could ship Linux first;
  dependency on 50 added, 50 removed from "Blocks".
- 2026-10-19: checkpoint 1 — test list approved (11 items) with the evidence that the pinned darwin
  prebuilds have no ggml GPU backend (arm64: CoreML only, no `libggml-metal.dylib`; x64: neither),
  so Metal is the Nix-built whisper-cpp and the prebuild path reports cpu/auto only. Answers: Metal
  surface faithful (`gpu:0` = `Metal (MTL0)`; `gpu:0` → `--device 0`, `cpu` → `--no-gpu`, `auto` →
  no flag); both CI architectures (`macos-15`, `macos-15-intel`), full tree nightly and on this
  item's PRs; Metal's positive half pinned by the 1.8.4 darwin lines and user-verified, the no-Metal
  half on CI; first cycle = test item 2, ticking only on the user's macOS run. Status: in-progress
  (test list approved).