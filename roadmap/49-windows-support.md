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

### Decided (checkpoint 2, 2026-10-22)

- **The upstream Windows tarball is not runnable on stock Windows.** The pinned
  `jiang1997` `whisper-1.8.4-windows-x64.tar.gz` (and ggml-org's own Windows zip) imports the MSVC
  runtime (`MSVCP140`, `VCRUNTIME140`, `VCRUNTIME140_1`, `VCOMP140`) and `vulkan-1.dll`, and the
  app's ONNX Runtime native imports the same MSVC runtime. A fresh Windows 11 guest fails with
  `0xC0000135` (whisper) and `Can't find dependent libraries` (ONNX, diarization). Checkpoint 1's
  "static build; `vulkan-1.dll` is loaded dynamically" was wrong.
- **We build and pin our own Windows asset** (`desktop-whisper-cpp-v1`,
  `whisper-cpp-1.8.4-windows-x64.tar.gz`): whisper.cpp 1.8.4 with `/MT`, `GGML_OPENMP=OFF` and
  `GGML_VULKAN=ON`; the Vulkan loader built from the pinned `vulkan-sdk-1.4.309.0` sources; and
  Microsoft's four redistributable CRT DLLs for ONNX Runtime — all extracted into the tool directory
  the bootstrap puts on PATH. The manual `windows-whisper-asset` CI job builds it and gates it on
  `dumpbin` imports plus a real CPU transcription.

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
- [x] (split from item 1, 2026-10-21) `windows-x64` whisper-cli (Vulkan), ffmpeg and Temurin JDK
      pins exist in `nix/versions.nix` and `versions.json`; the bootstrap downloads, sha-verifies
      and resolves `whisper-cli.exe`/`ffmpeg.exe` on Windows (user-verified on a Windows 11 guest,
      2026-10-22)
- [x] Backend starts on Windows via `gradlew.bat` + `stt.cmd` with a Windows-idiomatic
      STT_DATA_DIR and serves the UI; required-env-var errors are the same as on Linux
      (user-verified on a Windows 11 guest, 2026-10-22)
- [x] A full upload → transcribe → diarize run completes on Windows and its output matches Linux
      (user-verified on the Windows 11 guest, 2026-10-22)
- [x] Pausing a running task on Windows stops progress and resume continues from the next chunk
      without corrupting the transcript (per the decided checkpoint design) (user-verified on the
      Windows 11 guest, 2026-10-22)
- [x] Cancel and delete leave no orphan processes (whisper-cli/ffmpeg all gone) on Windows
      (user-verified on the Windows 11 guest, 2026-10-22)
- [x] Queue pause, reorder, retry and history behave as on Linux (user-verified on the Windows 11
      guest, 2026-10-22)
- [x] Device selection works with the GPU-enabled Vulkan build (or, on CPU-only CI, the flag path
      is verified and documented as CPU-first); a real-GPU run is user-verified if available
      (Windows 11 guest, 2026-10-22)
- [x] README documents Windows support and the bootstrap path, including the Vulkan driver
      requirement and the CPU fallback
- [x] (CI, inherited from 51 on 2026-10-14; amended 2026-10-21) This item adds its Windows leg to
      `.github/workflows/ci.yml`; it runs on every pull request and the nightly schedule, and no Nix
      job exists on Windows. Matched to the macOS cadence only if the first run's measured wall time
      is comparable (macOS e2e observed at 13–16 min on 2026-10-21): the measurement is recorded in
      the Approval log, and a slower run returns the cadence decision to the user before ticking
- [x] (e2e, CI Windows, moved from item 50 on 2026-10-15) The packaged portable zip runs on Windows
      with its per-user paths: item 50's packaged specs (journey, quit without orphan processes,
      first run with the model download and its persistence, download failure with Retry) pass
      against the unpacked build
- [x] (e2e, CI Windows, added 2026-10-21) The unsigned per-user MSI installs the same app: silent
      `msiexec /i … /quiet` installs under the per-user programs directory, item 50's packaged
      specs pass against the installed app, and `msiexec /x {ProductCode} /quiet` removes the
      program files while the data directory and model cache remain
- [x] (discovered, 2026-10-22, user-approved) On Windows the device probe passes the platform null
      device (`NUL`) as whisper-cli's `-f` input, not `/dev/null`
- [x] (discovered, 2026-10-22, user-approved) A whisper-cli that cannot load (missing DLL/shared
      library) is a `LaunchFailed` probe outcome, not `0 GPU(s)`; the app must not start offering
      cpu/auto as if the probe had succeeded (Windows loader codes, 2026-10-22)
- [x] (discovered, 2026-10-22, user-approved) A whisper-cli whose shared library is missing is a
      `LaunchFailed` probe outcome, not `0 GPU(s)`: Linux `ld.so` prints `error while loading shared
      libraries` and exits 127; macOS `dyld` prints `Library not loaded` and aborts (SIGABRT)
- [x] (discovered, 2026-10-23, from the root review) On Windows a bare tool name resolves through the
      PATH variable whatever its spelling (`Path`, `PATH`)
- [x] (discovered, 2026-10-23, from the root review; split and reworded 2026-10-23) A tool that exits
      while the backend is stopping never fails its task: the task goes back to the queue or stays
      RUNNING for restart recovery, on every platform
- [ ] (discovered, 2026-10-23, from the root review; split 2026-10-23) On Windows, a tool's console
      Ctrl-C exit (`0xC000013A`) counts as a stopped tool, so its task is left for recovery
- [x] (discovered, 2026-10-23, user-approved) On macOS, a tool's SIGINT exit (130, a terminal Ctrl-C
      that reaches the tool's process group) counts as a stopped tool, so its task is left for recovery
- [x] (discovered, 2026-10-23, from the root review; split 2026-10-23) Started with
      `STT_STOP_ON_STDIN_CLOSE=1`, the backend stops once its stdin closes
- [x] (discovered, 2026-10-23, from the root review; split 2026-10-23) Without
      `STT_STOP_ON_STDIN_CLOSE`, closing the backend's stdin leaves it running and serving
- [x] (discovered, 2026-10-23, from the root review; split 2026-10-23) On Windows the desktop quit
      closes the backend's stdin and force-kills the tree only if the backend has not exited within
      10 s
- [x] (discovered, 2026-10-23, from the root review) On Windows the desktop quit ends the backend
      within a bound even when `taskkill` fails, and the failure is logged
- [x] (discovered, 2026-10-23, from the root review) On Windows a cancelled run destroys its direct
      child and waits for it, bounded, without signals
- [x] (discovered, 2026-10-23, from the root review) A whisper-cli with an invalid image
      (`0xC000007B`) and a macOS `dyld` `Library not loaded` are both a `LaunchFailed` probe outcome
- [ ] (discovered, 2026-10-23, from the root review) The MSI spec fails when an installed
      `Speech-to-Text.exe` exists under `%ProgramFiles%` or `%ProgramFiles(x86)%`
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build` and Linux
      e2e stay green (re-run after the review findings)

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
- 2026-10-22: user-verified on a Windows 11 x64 guest (`C:\stt-49`, source copy of `88850329`,
  JDK 21 Temurin, git-ignored `handoff.md` only). Run 1, after the crash interrupted the earlier
  attempt (cache in `%USERPROFILE%\.gradle\stt-app` reused):
  - `gradlew.bat checkVersions` → `checkVersions: versions.json is in sync (Gradle 9.8.0, nixpkgs
    494ce7fd23ff, Windows pins present)`.
  - `gradlew.bat prepareRuntime` → BUILD SUCCESSFUL, no download: the cached whisper tarball,
    ffmpeg jar, whisper model and CAM++ model were re-hashed against `versions.json` (the cache
    check runs on every invocation). `whisper-cli.exe`, `whisper-server.exe` and `ffmpeg.exe`
    (with `avcodec-62.dll` and the bytedeco sibling DLLs) present under
    `%USERPROFILE%\.gradle\stt-app\tools\…`; the whisper tree was unpacked by Windows' own
    `tar.exe` in the earlier guest run and reused here, so the extraction path is observed and
    the extraction itself is not re-run in this recorded run.
  - `stt.cmd` → frontend `tsc --noEmit && vite build` (2169 modules), migrations 1→12 on a fresh
    database, `Device probe: 0 GPU(s) []` (no GPU passed through to the guest, no probe error),
    `Application started in 3.839 seconds`, `Responding at http://127.0.0.1:8080` via
    `gradlew.bat run` + `cmd /c …\stt-backend.bat` (exit 0 of the launcher path).
  - `GET /api/health` → `{"status":"ok"}`; `GET /` → `<title>Speech-to-Text</title>`; `stt.db`
    (114,688 bytes) plus `-wal`/`-shm` in `%APPDATA%\stt-app`.
  - Required env: unset → `STT_DATA_DIR must be set to the data directory (it holds stt.db)` and
    `EXIT=1`; with only the speaker model set → `STT_WHISPER_MODEL must be set to the whisper.cpp
    model file`.
  - Stop: Ctrl-C on `stt.cmd` leaves `:8080` closed (`netstat -ano | findstr :8080` empty) and no
    `whisper-cli`/`ffmpeg`; the only surviving `java.exe` is the Gradle 9.8 daemon (command line
    `org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.8.0`), which is expected and exits on
    `gradlew.bat --stop` or after the daemon idle timeout.
  - The item-3 checklist step 5 originally read "no java in `tasklist`"; corrected here: the daemon
    is expected, the backend must be gone.
  - Incidental: a hand-started `stt-backend.bat` (outside the Gradle `run` task, so without the
    managed-tools PATH) logged `Device probe failed; Executable not found: whisper-cli … offering
    CPU and auto` and failed an uploaded task — the designed behavior for a PATH without the
    managed tools, not a Windows defect.
  Items 2 and 3 tick as user-verified (no CI run yet; the Windows leg is item 10).
- 2026-10-22: cycle 4 (item 4) green, user-verified on the Windows 11 guest. Red first: the managed
  `gradlew.bat integrationTest` could not start because the root task launched the POSIX `gradlew`;
  after `cmd /c gradlew.bat` + `-PtestFilter`, the spec failed with `whisper-cli … exit -1073741515`
  (STATUS_DLL_NOT_FOUND), then with ONNX Runtime's `Can't find dependent libraries` once whisper
  ran. Fixes, each verified by the same run: a self-contained Windows asset built by the manual
  `windows-whisper-asset` job (`/MT`, `GGML_OPENMP=OFF`, pinned `vulkan-sdk-1.4.309.0` loader built
  from source, the four MSVC redistributable CRT DLLs for ONNX Runtime), published under
  `desktop-whisper-cpp-v1` and pinned (`9e467fcbe0de807c9e41ae9116bf76c5bedbe0b6551209b719571bc75257436c`);
  `checkVersions` now expects that asset name. Final run:
  `gradlew.bat checkVersions` in sync; `gradlew.bat prepareRuntime` downloaded and extracted the
  asset; `gradlew.bat integrationTest -PtestFilter=stt.engine.ProcessingEngineIntegrationSpec`
  `EXIT=0` (`BUILD SUCCESSFUL`) with the spec's JFK quote and one-speaker assertions green. Commits:
  `a3950f3e` (launcher + filter + quote assertions), `ef97a6ca`/`cc52c59a`/`da98092b`/`eca0f748`/
  `9bd18c2c` (asset job), `d03c215d`/`48b9c7e9` (pins). Item 4 ticked.
- 2026-10-22: discovered at the same checkpoint, awaiting the user's confirmation to append to the
  test list: (a) on Windows the device probe passes `/dev/null` as whisper-cli's input instead of
  `NUL`; (b) a whisper-cli that cannot load is reported as `0 GPU(s)` instead of a `LaunchFailed`
  probe outcome (entry in `docs/known-issues.md`).
- 2026-10-22: cycle 5 (item 5) green, user-verified on the Windows 11 guest. New
  `ProcessingEnginePauseIntegrationSpec` (Integration): the 11 s jfk fixture looped 30× (330 s) chunks
  into 300 s + 30 s, is paused while TRANSCRIBING, must stop moving below 1.0 with status PAUSED, then
  resumes to COMPLETED with the JFK quote and strictly increasing segment starts. The test was green
  on arrival on Linux (the signal path freezes the in-flight chunk); on Windows no signal freezes it,
  so the in-flight chunk finishes and the chunk gate stops the run. First guest run hit Ktor's
  `testApplication` 60 s real-time cap (`UncompletedCoroutinesError`); the spec now uses
  `runTestWithRealTime(timeout = 10.minutes) { runTestApplication { … } }`. Guest rerun: `EXIT=0`,
  `tests=1 failures=0`, 83.1 s. No production change. Commit `a3877016`. Item 5 ticked.
- 2026-10-22: cycle 6 (item 6) green, user-verified on the Windows 11 guest. New
  `ProcessingEngineCancelIntegrationSpec` (Integration, two scenarios) on the 330 s looped clip:
  cancel while a real whisper-cli is running → `Accepted`, status CANCELED, and the test JVM has no
  descendant executable named whisper-cli/ffmpeg (`ProcessHandle.descendants`, polled up to 10 s);
  delete of a running task → `Accepted`, row gone, no tool descendants either (delete cancels
  through the engine first). Green on Linux first (2 tests, 0 failures); guest rerun: `EXIT=0`,
  `tests=2 failures=0`, 9.8 s. No production change. Commit `57e8c01d`. Item 6 ticked.
- 2026-10-22: cycle 7 (item 7) green, user-verified on the Windows 11 guest. Ran the existing suites
  that own the four behaviors through `gradlew.bat -p backend test`: `TaskQueueSpec` (16),
  `ProcessingEngineQueuePauseSpec` (2), `ProcessingEngineRetrySpec` (3), `QueuePauseResumeSpec` (1),
  `QueueReorderSpec` (1), `QueueReorderMismatchSpec` (1), `TaskListSpec` (1) — `EXIT=0`, every suite
  `failures=0`. No production change. Item 7 ticked.
- 2026-10-22: approved test-only refactor after item 6: the pause and cancel specs' duplicated
  real-app harness (`Config`, CPU device, Ktor's 60 s-free `runTestWithRealTime` +
  `runTestApplication`, upload) is now `RealEngineApp.kt` (`realEngineApp` + `TaskRepository.upload`).
  Commit `aad9fb06`; both specs green (1 + 2 tests).
- 2026-10-22: discovered cycle (Windows probe input) green. New `DeviceProbeInputSpec` (red:
  `No parameter with name 'platform' found`; green: `ProcessPlatform.nullDevice`, derived from
  `windows`, feeds both the probe's `whisper-cli -f` and `ProcessRunner`'s stdin).
  `gradle -p backend check integrationTest` green (3m 2s). Guest runs: `-f /dev/null` →
  `error: input file not found` before any backend init; `-f NUL` → `ggml_vulkan: Found 1 Vulkan
  devices: 0 = NVIDIA GeForce RTX 3050 Laptop GPU`. Commits `eb3cc903`, `e87e5b9f`, `dfb1d8d0`.
  Item ticked; the app-level GPU evidence (probe line, `gpuNames`, a `--device 0` run) belongs to
  item 8's user-verified run and is pending.
- 2026-10-22: item 8 user-verified on the Windows 11 guest with the RTX 3050 passed through.
  Startup: `Device probe: 1 GPU(s) [GpuDevice(index=0, name=NVIDIA GeForce RTX 3050 Laptop GPU)]`.
  After saving the choice, `GET /api/settings` answered `"device":"gpu:0"`,
  `"availableDevices":["cpu","auto","gpu:0"]`, `"gpuNames":{"gpu:0":"NVIDIA GeForce RTX 3050 Laptop
  GPU"}`, `"deviceProbeError":null`. The running whisper-cli came from the pinned 9e467fcb asset and
  carried `--device 0`; `nvidia-smi` sampled 638 → 900 MiB used during the run, then a 100%
  utilization sample. The task was deleted before the transcript was captured. Item 8 ticked.
- 2026-10-22: discovered cycle (whisper-cli cannot load) green. New `DeviceProbeLoadFailureSpec`
  (red: `Probed(gpus=[]) … expected LaunchFailed`; green: `STATUS_DLL_NOT_FOUND` 0xC0000135 and
  `STATUS_INVALID_IMAGE_FORMAT` 0xC000007B map to `LaunchFailed` with a message naming the
  missing/invalid DLL, logged and surfaced as `deviceProbeError`). `gradle -p backend check` green
  after the hex-constant refactor. Commits `5b30e440`, `751f9289`. Item ticked; the POSIX
  loader-failure case (exit 127) is appended as a discovered item.
- 2026-10-22: the POSIX loader-failure scenario's wording corrected before its cycle: macOS `dyld`
  aborts with SIGABRT (it does not exit 127), so the loader's stderr line, not the exit code, is
  the cross-platform signal. Behavior unchanged; the user had approved the scenario.
- 2026-10-22: POSIX loader-failure cycle green. `DeviceProbeLoadFailureSpec`'s Linux scenario (red:
  `Probed(gpus=[]) … expected LaunchFailed`; green: `loadFailureMessage` matches `ld.so`'s and
  `dyld`'s stderr lines as well as the Windows status codes). `gradle -p backend check
  integrationTest` green on the behavior (2m55s); the approved test-only refactor `69e694c1` shares
  the probe setup and keeps `gradle -p backend check` green. Commits `f2b4e445`, `69e694c1`. Item
  ticked.
- 2026-10-22: item 9 cycle green. New `docs/check-readme-windows.mjs` pins the README's Windows
  facts (red: the ways-to-run row, the section, `gradlew.bat`, `stt.cmd`, `%APPDATA%\stt-app` and
  the ARM64 scope were missing; the Vulkan/CPU facts already existed). The README gains the
  Windows x64 section (bootstrap commands, prerequisites, downloads, data directory, Ctrl-C/chunk
  boundary, Vulkan driver and CPU fallback, no `dev` mode), the table row, the platform summaries,
  download path and the `stt.cmd` data row; AGENTS lists the new check. The three existing doc
  checks stay green. Commit `41490a3e`. Item ticked.
- 2026-10-22: item 10 green on CI, run #147 `39850684668` (branch `43551d8`, `workflow_dispatch`):
  `Windows (gradle check)` 5:00 and `Windows (pnpm test, pnpm build)` 1:44, both success; the two
  whisper asset jobs skipped (the new `assets` dispatch input defaults off); the run 32:24 overall
  (Linux e2e 27:52, unchanged). The first run (#145, `bd4d9457`) was the red: five backend tests
  failed on POSIX assumptions (`Files.setPosixFilePermissions` in `DatabaseSpec` and
  `ProcessingEngineFailureSpec`, `sleep` in `ProcessRunnerPlatformSpec`, `/`-paths in
  `WhisperCommandSpec`) and `filesUnder` threw on Windows' deletion-pending errors; Prettier failed
  on all 136 frontend files because the checkout was CRLF. Fixed by gating the four POSIX-only
  scenarios, deriving the expected paths, tolerating delete-pending paths in the test helper, and
  `.gitattributes` (`* text=auto eol=lf`, `.bat`/`.cmd` CRLF). Commits `bd4d9457` (jobs +
  aggregator), `3613515a` (asset jobs behind the dispatch input), `43551d81` (portability).
  Measured 5:00 against the macOS e2e band 13–16 min → no cadence change: every PR and nightly.
  Item ticked.
- 2026-10-23: item 11 green on CI and user-verified on the Windows 11 guest. Root cause of the four
  earlier reds: the Playwright launch env repoints `USERPROFILE`/`APPDATA`/`LOCALAPPDATA` at a
  fresh per-user tree, but the tree's `AppData\Roaming`/`AppData\Local` did not exist, so Chromium
  aborted during init with `0x80000003`; the helper now creates both (`windowsHomeEnv`) and the app
  is the only consumer of those variables it relies on. Also fixed on the way: `taskkill /T /F` for
  the desktop quit (no orphan processes), `.gitattributes` (LF checkout, CRLF `.cmd`/`.bat`),
  `java.exe` in the packaged backend launcher, the `%APPDATA%\stt-app` data directory in packaged
  mode, and the `unchecked` asset-dispatch logging. CI run #162
  `39878353214`, job `Windows (packaged zip e2e)`: all four specs pass (01 12.7 s, 02 5.9 s,
  03 14.8 s, 04 16.0 s; 50.4 s total, job ~7:30 from 00:34:04 to 00:41:37) against the unpacked
  `Speech-to-Text-0.1.0-win-x64.zip`; the run is green. Guest run of the same zip: 01 1.4 m,
  02 16.1 s, 03 1.2 m, 04 17.1 s, all pass. The job's 7:30 stays inside the macOS e2e band
  13–16 min → no cadence change. Commits `02ee7ce5` (helper + launch env), `8a72fa4e` (packaging).
  Item ticked; run recorded as user-verified.
- 2026-10-23: item 12 (per-user MSI) green on CI. New `e2e/desktop-tests/15-msi-install.spec.ts`
  (Windows-only): a silent `msiexec /i … /quiet /norestart` must put `Speech-to-Text.exe` under
  `%LOCALAPPDATA%\Programs` and never under Program Files; the installed app's first run downloads
  both models from the loopback server and transcribes the JFK clip; `msiexec /x {ProductCode}
  /quiet` (the code read from the per-user uninstall entry) must remove the program files while
  `stt.db` and both cached models remain. The Windows job also installs the MSI and runs item 50's
  packaged specs 01–04 against the installed app. Red 1: run #174 `39882646124` (`cb507d45`) —
  the zip specs passed, then `no MSI was produced: the win target builds only the zip`. Red 2: run
  #179 `39884473983` (`57f5211a`, `msi` target with `perMachine: false`, `oneClick`,
  `runAfterFinish: false`) — the zip built, the WiX link failed with `LGHT0094 : The identifier
  'Icon:SpeechtoTextIcon.exe' could not be found` (electron-builder emits the `<Icon>` element only
  when an application icon is configured). Green: run #183 `39886849354` (`b884993f`, `icon:
  icon.png`), job `Windows (packaged zip e2e)` success in 7:00 — zip specs 0:29, MSI install +
  installed-app specs 01–04 0:38, MSI installer spec 0:27. The run failed only on `macOS (gradle
  check)`, the temp-dir watch flake recorded in `docs/known-issues.md` (`7b52120b`); #179's
  `macOS (e2e)` red was `06-playback.spec.ts:92` (`00:01` expected, `00:02` received after pause),
  code this branch does not touch, recorded in the known-issues row for that spec. Not run on the
  Windows 11 guest: CI-verified only. Commits `cb507d45` (spec + CI step), `57f5211a` (MSI target +
  installed-app specs), `b884993f` (icon). Item ticked.
- 2026-10-23: approved test-only refactor after item 12: `15-msi-install.spec.ts`'s two copies of
  the kept-data assertions (after the app's run and after the uninstall) are now
  `expectUserDataKept(paths, models)`. Type check (`tsc -p desktop/tsconfig.json`) and Prettier
  green locally; the spec itself is Windows-only and runs in the next CI run.
- 2026-10-23: CI layout changes, user-requested at the checkpoint: `Windows (packaged zip e2e)` now
  needs the Windows checks instead of the Linux ones (`0449deb3`; it had shared the Linux jobs'
  `needs` since `a51c05cf`), and every job's name starts with the platform it targets — the Linux
  jobs were `Backend`/`Frontend`/`Docs`/`E2E`/`Desktop`/`Nix (…)`; `CI (all checks)` keeps its name,
  the check branch protection requires (`775798d4`). `actionlint` clean. Known-issues evidence on
  the way: the `06-playback` paused-time race (`91564cfc`), a second quit-spec cleanup failure in
  run #185 (`95019157`); the temp-dir watch flake (two macOS `gradle check` failures, runs 183 and
  187) moved to roadmap item 77 (`e4cad467`).
- 2026-10-23: last item (must not break) green. Locally on `a4a150a2`: `gradle check` (ktlint, 348
  tests, 0 failures, test outputs cleaned first), `gradle integrationTest` (80 Integration tests,
  0 failures), `pnpm test` (177 vitest tests, Prettier clean) and `pnpm build` (`tsc --noEmit`,
  `vite build`). On CI: run #190 `39893707847` (`ee715ea3`, `workflow_dispatch`, attempt 1) —
  every job success, `CI (all checks)` included, 19:04 overall: `Linux (e2e)` 10:46, the
  `.deb`/flake/bundle desktop jobs, `Linux (gradle integrationTest)`, the three Nix jobs, all
  macOS jobs (`macOS (e2e)` 15:08, the first macOS e2e green since #174) and the Windows path
  (checks 3:16, then `Windows (packaged zip e2e)` 9:18 with the refactored MSI spec, done 12:40
  after the start). The Windows path ends before the Linux bundle job (13:55) and macOS e2e, so the
  gate change does not lengthen the run. Item ticked; every test-list item is ticked. Status:
  in-review.
- 2026-10-23: returned from the root agent's review with seven findings (case-sensitive `Path`
  lookup on Windows; the decided known-stopped-by-us flag not implemented; the Windows desktop
  `stop()` neither observes nor escalates `taskkill`; the Windows pnpm path in `build.gradle.kts`;
  untested Windows branches and dead guards; a tautological MSI assertion and an unbounded README
  section check; an unpinned Vulkan SDK action and a wrong `versions.nix` comment). Status:
  in-progress.
- 2026-10-23: review decisions (user). Finding 2: **implement the decided known-stopped-by-us
  flag** — the backend marks itself stopping when the application starts to stop, and a tool exit
  after that is a cancellation on every platform (the task stays RUNNING for recovery); on Windows
  `0xC000013A` (console Ctrl-C) counts as stopped; the Windows desktop quit closes the backend's
  stdin (honoured only when the desktop sets a switch variable), waits up to 10 s, then
  `taskkill /T /F`. A tool killed from outside (Task Manager, exit 1) still ends FAILED on Windows.
  Finding 4: **remove** the Windows branch — `e2e`/`e2eInstall`/`e2eBrowsers` fail fast on Windows
  with a pointer to the packaged-app e2e; `appEnv()`'s pnpm PATH entry is corrected. Seven
  discovered items appended to the test list (a proposed "Gradle e2e finds pnpm on Windows" item
  dropped with the removal); the must-not-break item is unticked until the final re-run. The MSI
  item's red is recorded by pointing the new check at the per-user directory, where the exe does
  exist. Order: CI action pin and `versions.nix` comment; PATH case; stopping flag → graceful
  desktop stop → `taskkill` failure; the e2e removal; the cancel-kill and loader-code coverage; the
  MSI check; the refactors (dead `pause()`/`resume()` returns, `require(platform.signals)` in
  `ProcessTree`, `TestEngine`'s Windows-only `!Files.exists`, the bounded README section, the pause
  spec's wait for whisper-cli to be gone), each offered at a checkpoint.
- 2026-10-23: review cycle 1 (PATH spelling) green. Red: `ProcessRunnerWindowsSpec`'s new case, a
  Windows env of `mapOf("Path" to …)`, failed with `ExecutableNotFoundException: Executable not
  found: ffmpeg` (`ProcessRunner.kt:166`). Green: `searchPath()` matches the variable's name
  ignoring case on Windows and keeps the exact `PATH` lookup elsewhere; `gradle check` and
  `gradle integrationTest` (80 Integration tests) green. Ticked; the Windows-only observation
  follows with the next CI run or the guest. Commit `9165c8c5`.
- 2026-10-23: checkpoint after review cycle 1 (user: proceed with the proposals). Approved
  test-only refactor: the two Windows cases in `ProcessRunnerWindowsSpec` share
  `spawnedOnWindows(env, command)` (`71c6cee5`; `gradle check` and `gradle integrationTest` green).
  The stopping-flag item is split in two — the flag on every platform, and the Windows Ctrl-C exit
  code — so each is one test. Next cycle: the flag.
- 2026-10-23: review cycle 2 (stopping flag) green. New `ProcessRunnerStoppingSpec`, Darwin and
  Windows (Linux shares Darwin's path except the group it signals, so no real group is touched):
  with the backend stopping, a tool that exits 1 must make the run a `CancellationException`. Red
  first on the missing `stopping` seam (compile), then, with the seam unused, `Expected exception
  java.util.concurrent.CancellationException but no exception was thrown` (both cases). Green:
  `ProcessRunner` throws a cancellation for a non-zero exit while `stopping()` holds;
  `processingEngine` sets the flag on Ktor's `ApplicationStopPreparing`. A throwaway spec (deleted)
  confirmed the real Netty server raises `StopPreparing` before `Stopping`/`Stopped`. The public
  constructor's new `stopping` lambda made three trailing-lambda test calls ambiguous; they now
  name `startProcess`. `gradle check` (351 tests) and `gradle integrationTest` (80 Integration
  tests) green, frontend and docs checks green. Commit `abb53e2a`. Not ticked yet: the engine turns
  that cancellation into QUEUED while its coroutine is still active (item 42's requeue) and leaves
  RUNNING only once the engine itself is cancelled, so the item's "leaves its task RUNNING" is
  narrower than the behaviour; a rewording is proposed at the checkpoint.
- 2026-10-23: checkpoint after review cycle 2 (user: refactor 1, defer 2, add the discovered
  scenario, go on). The stopping-flag item is reworded to the behaviour — never FAILED; back to the
  queue, or RUNNING for restart recovery — and ticked on cycle 2's evidence. Discovered and
  appended: on macOS (no `setsid`, so tools share the terminal's process group) a terminal Ctrl-C
  can kill whisper-cli with SIGINT (exit 130) before the JVM's shutdown hook sets the flag; an
  inference from the code, not reproduced. Approved refactor: one shared exited-process test fake
  for the `stt.process` specs. Deferred: merging the exit-interruption checks into one function,
  to be offered again after the exit-code cycles.
- 2026-10-23: approved refactor `af638842`: `ExitedProcess(exitCode, pid)` in its own test file
  replaces the three private fakes of `ProcessRunnerPlatformSpec`, `ProcessRunnerWindowsSpec` and
  `ProcessRunnerStoppingSpec`; all gates green (351 unit tests).
- 2026-10-23: review cycle 3 (Windows console Ctrl-C) green locally. New case in
  `ProcessRunnerWindowsSpec`: with the backend not (yet) stopping, a tool exiting
  `0xC000013A` must make the run a cancellation. Red: `Expected exception
  java.util.concurrent.CancellationException but no exception was thrown`. Green: `ProcessRunner`
  maps `STATUS_CONTROL_C_EXIT` on Windows to a cancellation, next to the SIGTERM-143 check.
  `gradle check` (352 tests), `gradle integrationTest`, frontend and docs checks green. Commit
  `6e2a1905`. Ticks with the next CI run's `Windows (gradle check)`, which runs this spec on
  Windows.
- 2026-10-23: review cycle 4 (macOS terminal Ctrl-C) green. Scope confirmed by the user: macOS only;
  on Linux, tools run in their own `setsid` session, so a 130 there is a deliberate SIGINT and stays
  a failure. New case in `ProcessRunnerPlatformSpec` (Darwin platform): a tool exiting 130 must make
  the run a cancellation. Red: `Expected exception java.util.concurrent.CancellationException but
  no exception was thrown`. Green: `ProcessRunner` maps exit 130 to a cancellation where signals
  exist but process groups do not (Darwin). `gradle check` (353 tests), `gradle integrationTest`,
  frontend and docs checks green. Commit `e7fbe8cd`. Ticked: the seam test runs on every host and on
  the macOS CI job; the Ctrl-C race itself stays an inference from the code, not reproduced.
- 2026-10-23: checkpoint after review cycle 4 (user: do the refactor, approve the split and the
  next test). Approved refactor `170de287`: the four exit checks in `runExclusively` are one
  `interruption(exitCode)` function (SIGTERM 143, Windows `STATUS_CONTROL_C_EXIT`, Darwin SIGINT 130,
  the stopping flag); `gradle check` (353 tests) and `gradle integrationTest` green. Gate slip on
  that commit: the frontend command's output filter hid vitest's exit code, and that full run had
  failed 3 of 177 on 5 s timeouts under host load (11–14 on 8 cores); the commit changes only
  `ProcessRunner.kt`, the failing files passed alone, and a re-run on the same tree passed 177/177.
  Recorded in `docs/known-issues.md`; the gate now checks vitest's exit code itself. The desktop
  graceful-stop item is split in two: the backend stops on stdin close when
  `STT_STOP_ON_STDIN_CLOSE=1` is set, and the Windows desktop `stop()` closes stdin before a bounded
  force-kill. Next test: the backend half.- 2026-10-23: review cycle 5 (backend stops on stdin close) green. New Integration spec
  `StopOnStdinCloseIntegrationSpec`: the app runs as a real JVM with `STT_STOP_ON_STDIN_CLOSE=1`,
  serves, then its stdin is closed and it must exit within 30 s. Red:
  `expected:<true> but was:<false>` at `:49` (the app kept running; the first `shouldBe true` at
  `:45` passed, so the red is the stop, not the startup). Green: `Config.stopOnStdinClose` and
  `stopWhenClosed` in `Main.kt` (a daemon-scoped blocking read on the IO dispatcher that calls
  `server.stop()`, the same stop a SIGTERM's shutdown hook performs; an `IOException` is logged and
  treated as a close). `gradle check` (354 tests), `gradle integrationTest` (401, 80 executed),
  frontend and docs checks green. Commit `0e1a3cb1`. Not ticked: the item's "without the variable it
  ignores stdin" half is untested — proposed as its own test at the checkpoint.
- 2026-10-23: checkpoint after review cycle 5 (user: refactor, split, confirm the next test).
  Approved refactor `a815aa5c`: `stt/BackendProcess.kt` (`startBackend`, `awaitServing`, `freePort`)
  replaces the duplicated JVM spawn in `StopOnStdinCloseIntegrationSpec` and the private helpers in
  `UpgradeCrashScratchIntegrationSpec`; both specs pass (1 test each), `gradle check` (354) and
  `gradle integrationTest` green, frontend and docs checks green (a first frontend run flaked with
  three 5 s timeouts while the Gradle run held the machine; the re-run passed 177/177). The
  stdin-close item is split: the test-list line for the flag remains, and a new line pins the
  negative — without the variable, closing stdin leaves the backend running and serving. Next test:
  the negative.
- 2026-10-23: review cycle 6 (stdin ignored without the switch) green. Second case in
  `StopOnStdinCloseIntegrationSpec`: without `STT_STOP_ON_STDIN_CLOSE` (started as `gradle run` and
  `stt.cmd` start it), closing stdin must leave the backend running and serving. Red recorded
  honestly by temporarily making the watcher unconditional (`if (config.stopOnStdinClose || true)`,
  reverted in the same cycle): `expected:<true> but was:<false>` at `:63` (`app.isAlive`). Green on
  the restored conditional. `gradle check` (354 tests), `gradle integrationTest` (402, 82 executed),
  frontend and docs checks green. Commit `658e8615`. Both stdin-close items ticked.
- 2026-10-23: review cycle 7 (Windows desktop quit, graceful first) green. The stop logic moved out of
  `startBackend`'s closure into `stopBackend(child, {platform, timeoutMs, forceKill})` (`desktop/src/backend.ts`),
  and the desktop package got its first test runner: `node --test src/backend.test.ts`, added to
  `pnpm --dir desktop test` (which the three desktop CI jobs already run), with
  `desktop/tsconfig.test.json` type-checking the spec against the same globals as `src`.
  The spec's fake process records stdin ends, signals and force-kills; `node:test` mock timers
  drive the 10 s wait deterministically. Red (after the extraction, before the fix): "the graceful
  ask is the closed stdin" (`false !== true`). Green: on Windows `stopBackend` ends the child's
  stdin, force-kills with `taskkill /T /F` only after the wait, and sends no signal; `startBackend`
  pipes stdin and sets `STT_STOP_ON_STDIN_CLOSE=1` there. `stdio` is one literal
  `['pipe', 'pipe', 'pipe']` so the type of `child.stdout`/`stderr` stays non-null; on POSIX the
  stdin pipe is never written or read. Gates: `node --test` 1 test, `tsc` for the app, the test
  project and the scripts, Prettier, `gradle check`, `gradle integrationTest`, frontend and docs
  checks. Commit `8401caf6`. Not ticked: "only if the backend has not exited within 10 s" — the
  branch where the backend exits in time — is untested; proposed as its own test.
- 2026-10-23: review cycle 8 (no force-kill of a gone backend) green. Second test in
  `desktop/src/backend.test.ts`: a backend that exits within the wait, and one that had already
  exited, are never force-killed (the first also asserts the stop resolves on its exit, the second
  that stdin is not touched). Red: `AssertionError: an exited backend is not force-killed` — the
  deferred `taskkill` fired regardless of the exit, a bug the extraction introduced. Green: the
  timer checks `exitCode === null` first. `node --test` 2 tests, the three `tsc` projects, Prettier,
  `gradle check`, `gradle integrationTest`, frontend and docs checks green. Commit `f27cc434`. The
  desktop-quit item is ticked.
- 2026-10-23: review cycle 9 (failed taskkill) green. `stopBackend`'s Windows branch now asks
  `forceKill` for a verdict (`Promise<boolean>`), logs a failure through an injected `log` (the
  console by default), and ends the JVM with `child.kill()` when `taskkill` could not; if even that
  fails it logs and settles the quit instead of hanging. The default spawner listens for `error`
  and `exit`, so the Electron main process no longer sees an unhandled `error` event. New test in
  `desktop/src/backend.test.ts`: a failing force-kill must log exactly one line naming `taskkill`
  and call `kill()` with no signal; the three earlier cases' stubs now return `true`. Red:
  `one line names the failure, got []`. `node --test` 3 tests, the three `tsc` projects, Prettier,
  `gradle check`, `gradle integrationTest`, frontend and docs checks green. Commit `50e49b1f`.
  Item ticked.
- 2026-10-23: review cycle 10 (cancelled Windows run) green. New case in
  `ProcessRunnerWindowsSpec`: the run's coroutine is cancelled from inside the spawn seam, so the
  failure path takes `terminateDirectly`; a `RecordingProcess` fake records `destroyForcibly`, the
  bounded `waitFor` (300 ms grace) and any signal. Green on arrival — the code already did this —
  so the red was recorded by temporarily removing the `destroyForcibly()` call (reverted in the same
  cycle): `expected:<true> but was:<false>` on `child.destroyed`. `gradle check` (355 tests),
  `gradle integrationTest` (402, 82 executed), frontend and docs checks green. Commit `f6fc4a78`.
  Item ticked.
- 2026-10-23: review cycle 11 (loader codes) green. Two cases added to
  `DeviceProbeLoadFailureSpec`: `STATUS_INVALID_IMAGE_FORMAT` (`0xC000007B`) on Windows, and a macOS
  `dyld` "Library not loaded" (exit 134). Green on arrival — the mapping already existed — so the red
  was recorded by temporarily removing the `STATUS_INVALID_IMAGE_FORMAT` branch and the
  "Library not loaded" line (backup restored in the same cycle): both cases failed with
  `Probed(gpus=[]) is of type … but expected … LaunchFailed`. `gradle check`, `gradle integrationTest`,
  frontend and docs checks green. Commit `9878c70a`. Item ticked.
