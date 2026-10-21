# 49 — Windows support

Status: in-progress
Depends on: [33](../docs/specs/gradle-bootstrap.md) (done; the no-Nix path), [48](../docs/specs/macos-support.md) (done; its portable process-control seam is the foundation), [51](../docs/specs/ci-verification.md) (done; a Windows CI runner verifies — local virtualization is the fallback), [50](../docs/specs/electron-desktop-app.md) (done; the Linux desktop app and its packaged specs, which the Windows packaged-app item extends)
Blocks: —

## Goal

The app runs on Windows 10/11 x64 without WSL: built and launched through the Gradle wrapper
(`gradlew.bat`, item 33's bootstrap path) and a `.cmd` launcher, speaking to pinned Windows binaries
of whisper-cli and ffmpeg, with working queue semantics (pause at the next chunk boundary, cancel,
resume, reorder, retry) and a green e2e journey. It is delivered as a portable zip and an unsigned
per-user MSI. Windows is a supported platform in the docs; the POSIX-only pieces are replaced or
cleanly abstracted, not papered over. GPU-accelerated transcription works with a GPU-enabled
whisper-cli build (Vulkan, the pinned upstream Windows tarball).

## Evidence

Filed 2026-10-13 from the portability pass (verified reads, nothing executed):

- **The runtime cannot start** — `ProcessRunner.kt:73` spawns every command as `setsid <cmd>`
  (util-linux; absent on Windows, no fallback per ADR 0020). Executable resolution is POSIX-shaped:
  PATH split on `:` (`ProcessRunner.kt:164-171`), `Files.isExecutable` (`:158-161`), no `.exe`
  handling (`ChunkTranscriber.kt:35`, `DeviceProbe.kt:30`), stdin/probe via `/dev/null`
  (`ProcessRunner.kt:136`, `DeviceProbe.kt:35`).
- **Pause/resume/terminate are POSIX-only** — `LibC.kt` JNA-loads libc `kill(2)`; signals do not
  exist on Windows. `ProcessTree` kills by negated pid + SIGSTOP/SIGCONT/KILL; the SIGTERM-exit-143
  detection (`ProcessRunner.kt:89,173-174`) never applies. The in-flight freeze (item 14's pause of a
  running whisper-cli) has no Windows equivalent without process reworking.
- **No Windows tool artifacts** — `nix/versions.nix` download tables have linux-x64/darwin-* only;
  the bootstrap cannot fetch Windows whisper-cli/ffmpeg, and there is no GPU-enabled Windows build.
- **Nix is Linux/macOS-only by nature** — devenv/flake cannot deliver this; item 33's Gradle
  bootstrap is the only path. `gradlew.bat` exists and is complete; the Gradle build is OS-agnostic.
- **Scripts are bash** — `dev.sh`, `backend/dev-run.sh` use POSIX traps/kill; the e2e harness uses
  `process.kill(-pid)` group kill; Windows needs cmd/PowerShell equivalents.
- **Portable already** — sqlite-jdbc/onnxruntime Maven natives are per-OS; data paths are
  `Path.of`-based; the frontend (pnpm/Vite) is OS-agnostic.
- **No CI exists**; item 51 provides the Windows runner.

## Decided (consultation, 2026-10-13)

- **Pause semantics**: pause at the next chunk boundary via the existing PauseGate — the run stops
  between whisper runs (progress stops, same task state machine, no data loss). The in-flight SIGSTOP
  freeze is POSIX-only and is not attempted on Windows (no SuspendThread/job-object suspend).
- **GPU support is in scope**: a GPU-enabled Windows whisper-cli build and working device selection;
  the compute backend (CUDA vs DirectML vs Vulkan) is chosen during implementation, with the
  DeviceArgs flags verified against that build.
- **Verification**: local virtualization plus a GitHub Actions Windows runner per [51](../docs/specs/ci-verification.md).
- **Delivery**: `gradlew.bat` bootstrap + a `.cmd` launcher, zip layout, pinned sha-verified Windows
  artifacts in versions.nix.

### Decided (checkpoint 1, 2026-10-21)

- **GPU backend: Vulkan.** The pinned upstream `whisper-1.8.4-windows-x64.tar.gz` (sha256
  `cfc2a9f82e6db9c5fe11e3637d6be8660ae169a527febfbc5d35a5e0d96ca85b`) is a static PE32+ build with
  ggml-vulkan; `vulkan-1.dll` is `LoadLibrary`-loaded, so a host without an ICD falls back to CPU.
  The existing Vulkan probe parser and `gpu:N` → `--device N` path carry over unchanged. CUDA is a
  possible later item (a second pinned asset, CUDA DLL staging, a CUDA parser and selector); on the
  benchmarked RTX 4070 Mobile (whisper.cpp issue #89) Vulkan's encoder was ~18% slower on
  large-v3-turbo and the one end-to-end run (medium.en, 75 min) was ~4% slower — a throughput
  tradeoff, not a capability one, and Vulkan is what makes AMD/Intel GPUs usable at all.
- **CPU-first CI.** Standard GitHub-hosted Windows runners have no GPU; GPU runners are larger
  runners for organization plans. The Windows leg verifies startup, the CPU path, the zero-GPU probe
  and the `gpu:N` fallback; a real-GPU observation is user-verified on a Windows VM/host if
  available, and the docs state the Vulkan driver requirement.
- **Process control:** the backend terminates the direct child (`Process.destroyForcibly`;
  whisper-cli/ffmpeg do not fork), pause is the existing chunk-boundary `PauseGate` with no
  signals, and the exit-143 detection becomes a known-stopped-by-us flag. The desktop app quits
  with `taskkill /T /F` on the live backend pid (Node cannot send a catchable SIGTERM on Windows)
  and spawns it with `windowsHide: true`; an abrupt quit is crash-safe (SQLite) and RUNNING tasks
  recover (ADR 0004).
- **Test list:** approved as 13 items, with item 1 split into the spawn seam and the Windows pins,
  and a new MSI item. Windows-CI items tick only on a reported run; user runs are recorded.
- **Windows CI cadence:** every pull request and the nightly schedule, unless the first run's
  measured wall time says otherwise; the decision is recorded with that measurement.
- **Packaging:** the portable zip plus an unsigned per-user MSI (WiX, `perMachine: false`); data in
  `%APPDATA%\stt-app`, models in `%LOCALAPPDATA%\stt-app\models`.

## Proposal

1. Consume item 48's process-control seam: a Windows implementation replacing setsid/signals —
   terminate via job objects/JNA or `taskkill /T /F`; pause via the checkpoint gate; SIGTERM-exit-143
   detection replaced by known-stopped-by-us tracking.
2. Runtime plumbing: Windows null device (`NUL`), PATH split on `;` + `.exe` candidate handling,
   `Files.isExecutable` → existence check, `%APPDATA%` data-dir default.
3. Tooling: windows-x64 whisper-cli (GPU-enabled) and ffmpeg entries (pinned, sha-verified) in
   `nix/versions.nix`/`versions.json`; `.cmd` launcher; e2e process cleanup without POSIX group kill.
4. Docs: Windows platform section; AGENTS/README updates.

## Open questions (answered 2026-10-21)

- GPU compute backend on Windows? → **Vulkan**: the pinned upstream Windows tarball is
  Vulkan-enabled, reuses item 45's parser and `--device N`, and covers NVIDIA/AMD/Intel; CUDA is a
  possible later item. See the Decided section for the benchmark evidence.
- Does the Windows CI runner include a GPU? → **No** on the standard free runners (GPU runners are
  larger runners for organization plans), so CI is CPU-first and the flag/fallback path is what it
  verifies; a real-GPU run is user-verified if a Windows VM or host exposes the GPU.

## Test list (approved 2026-10-21; behaviors only)

(Windows-CI items tick only on a reported CI run, never on a local proxy; a run the user performs
on a Windows VM or host is recorded in the Approval log as user-verified.)

- [x] (split from item 1, 2026-10-21) On Windows a command is spawned without `setsid`, a bare tool
      name resolves through the `;`-separated PATH to `<name>.exe`, stdin is `NUL`, and
      pause/resume send no signals (pause happens at the chunk gate)
- [ ] (split from item 1, 2026-10-21) `windows-x64` whisper-cli (Vulkan), ffmpeg and Temurin JDK
      pins exist in `nix/versions.nix` and `versions.json`; the bootstrap downloads, sha-verifies
      and resolves `whisper-cli.exe`/`ffmpeg.exe` on Windows
- [ ] Backend starts on Windows via `gradlew.bat` + `stt.cmd` with a Windows-idiomatic
      STT_DATA_DIR and serves the UI; required-env-var errors are the same as on Linux
- [ ] A full upload → transcribe → diarize run completes on Windows and its output matches Linux
- [ ] Pausing a running task on Windows stops progress and resume continues from the next chunk
      without corrupting the transcript (per the decided checkpoint design)
- [ ] Cancel and delete leave no orphan processes (whisper-cli/ffmpeg all gone) on Windows
- [ ] Queue pause, reorder, retry and history behave as on Linux
- [ ] Device selection works with the GPU-enabled Vulkan build (or, on CPU-only CI, the flag path
      is verified and documented as CPU-first); a real-GPU run is user-verified if available
- [ ] README documents Windows support and the bootstrap path, including the Vulkan driver
      requirement and the CPU fallback
- [ ] (CI, inherited from 51 on 2026-10-14; amended 2026-10-21) This item adds its Windows leg to
      `.github/workflows/ci.yml`; it runs on every pull request and the nightly schedule, and no Nix
      job exists on Windows. Matched to the macOS cadence only if the first run's measured wall time
      is comparable (macOS e2e observed at 13–16 min on 2026-10-21): the measurement is recorded in
      the Approval log, and a slower run returns the cadence decision to the user before ticking
- [ ] (e2e, CI Windows, moved from item 50 on 2026-10-15) The packaged portable zip runs on Windows
      with its per-user paths: item 50's packaged specs (journey, quit without orphan processes,
      first run with the model download and its persistence, download failure with Retry) pass
      against the unpacked build
- [ ] (e2e, CI Windows, added 2026-10-21) The unsigned per-user MSI installs the same app: silent
      `msiexec /i … /quiet` installs under the per-user programs directory, item 50's packaged
      specs pass against the installed app, and `msiexec /x {ProductCode} /quiet` removes the
      program files while the data directory and model cache remain
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build` and Linux
      e2e stay green

## Acceptance criteria

- Windows x64 is a documented, tested platform for the runtime and queue semantics; the POSIX-only
  assumptions (setsid, signals, /dev/null, PATH, exit-143) are gone or cleanly abstracted; every
  spawned tool is a pinned Windows artifact (the Vulkan GPU build included); the e2e journey passes
  on Windows.
- The app ships as a portable zip and an unsigned per-user MSI, both carrying the bundled JRE,
  backend, UI and pinned tools.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed from the Windows portability pass (setsid/signals/143/PATH/NUL/no artifacts).
- 2026-10-13: consultation — checkpoint pause at chunk boundaries; GPU-enabled whisper-cli in scope
  (backend chosen at implementation); verification via virtualization + CI runner (dependency 51);
  delivery `gradlew.bat` + `.cmd` zip. Status: spec-approved.
- 2026-10-15: item 50's test item "The packaged app runs on macOS and Windows with their per-user
  paths" moved here (Windows half) by the user's decision, so item 50 could ship Linux first;
  dependency on 50 added, 50 removed from "Blocks".
- 2026-10-21: checkpoint 1 — the GPU backend is **Vulkan**, not CUDA: the pinned upstream
  `whisper-1.8.4-windows-x64.tar.gz` is Vulkan-enabled and `vulkan-1.dll` is loaded dynamically, so
  a driver-less host falls back to CPU; item 45's parser and `gpu:N` contract are reused. Benchmark
  evidence discussed: whisper.cpp issue #89's RTX 4070 Mobile (Win11) encoder timings, CUDA vs
  Vulkan — large-v3-turbo 249 vs 294 ms (+18%), medium 154 vs 166 ms (+8%), and one 75-minute
  medium.en transcription 295.6 vs 308.7 s (+4.4%) — so the CUDA gap is throughput, not capability,
  and CUDA is deferred as a possible later item. Standard GitHub-hosted Windows runners have no GPU
  (GPU runners are larger runners for organization plans), so CI verifies the CPU path, the zero-GPU
  probe and the `gpu:N` fallback; the user will record a Windows VM run and, if a host or VM exposes
  the GPU, a real-GPU run.
- 2026-10-21: checkpoint 1 — stop design: direct-child `destroyForcibly` in the backend, chunk-gate
  pause with no signals, a known-stopped-by-us flag instead of exit 143, and `taskkill /T /F` with
  `windowsHide: true` for the desktop quit (no tray/console window). Test list approved as 13 items:
  item 1 split into the spawn seam and the Windows pins; a new MSI item added; Windows CI on every
  PR and nightly, with the first measured wall time compared to the macOS e2e band (13–16 min) and
  the cadence returned to the user if slower. First cycle: the Windows spawn seam. Status:
  in-progress (test list approved).
- 2026-10-21: user-verification path: QEMU with the NVIDIA GPU passed through to a Windows
  guest, so the VM run can cover both the CPU/queue semantics and the real Vulkan GPU path
  (driver permitting; a dual boot remains the fallback if passthrough is refused). Recorded as
  user-verified when run.
- 2026-10-21: cycle 1 (Windows spawn seam) green and ticked: `ProcessRunnerWindowsSpec` red first
  on the missing `WINDOWS`/`env`/`signals` API, then green; full `gradle -p backend check` green.
  Commits: `920973e0` (test + code), `c760137a` (approved refactor: absent signal numbers),
  `9876ea76` (approved refactor: platform facts moved to `ProcessRunnerPlatformSpec`).
- 2026-10-21: cycle 2 (Windows pins) green locally: `checkVersions` failed first on the missing
  `windows-x64` platform, then passed with the three pins; `ktlintCheck` green. The artifacts were
  downloaded and their sha256 values match; contents confirmed `whisper-cli.exe` (Vulkan),
  `ffmpeg.exe` + DLLs and the JDK's jmods. `nix` was not on the workspace's PATH, so `versions.json`
  was regenerated with `jq -S` in the flake's shape; `versions-sync` awaits a Nix run. Commits:
  `aa12eb5c` (test + pins), `27d8cb32` (approved refactor: `platformExecutable`), `7964a134`
  (approved refactor: `verifyWindowsPins`). Item 2 ticks with the Windows run in item 3.