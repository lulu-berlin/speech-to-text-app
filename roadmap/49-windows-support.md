# 49 — Windows support

Status: spec-approved
Depends on: [33](../docs/specs/gradle-bootstrap.md) (done; the no-Nix path), [48](48-macos-support.md) (spec-approved; its portable process-control seam is the foundation), [51](../docs/specs/ci-verification.md) (done; a Windows CI runner verifies — local virtualization is the fallback), [50](../docs/specs/electron-desktop-app.md) (done; the Linux desktop app and its packaged specs, which the Windows packaged-app item extends)
Blocks: —

## Goal

The app runs on Windows 10/11 x64 without WSL: built and launched through the Gradle wrapper
(`gradlew.bat`, item 33's bootstrap path) and a `.cmd` launcher, speaking to pinned Windows binaries
of whisper-cli and ffmpeg, with working queue semantics (pause at the next chunk boundary, cancel,
resume, reorder, retry) and a green e2e journey. Windows is a supported platform in the docs; the
POSIX-only pieces are replaced or cleanly abstracted, not papered over. GPU-accelerated
transcription works with a GPU-enabled whisper-cli build.

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

## Proposal

1. Consume item 48's process-control seam: a Windows implementation replacing setsid/signals —
   terminate via job objects/JNA or `taskkill /T /F`; pause via the checkpoint gate; SIGTERM-exit-143
   detection replaced by known-stopped-by-us tracking.
2. Runtime plumbing: Windows null device (`NUL`), PATH split on `;` + `.exe` candidate handling,
   `Files.isExecutable` → existence check, `%APPDATA%` data-dir default.
3. Tooling: windows-x64 whisper-cli (GPU-enabled) and ffmpeg entries (pinned, sha-verified) in
   `nix/versions.nix`/`versions.json`; `.cmd` launcher; e2e process cleanup without POSIX group kill.
4. Docs: Windows platform section; AGENTS/README updates.

## Open questions

- GPU compute backend on Windows (CUDA / DirectML / Vulkan) — decided during implementation against
  runner availability; GPU device enumeration reconciles with items 45/48.
- Whether the Windows CI runner includes a GPU (device-selection tests) or runs CPU-only with the
  flag path verified separately.

## Test list (proposed; behaviors only)

- [ ] Backend starts on Windows via `gradlew.bat` + the `.cmd` launcher with a Windows-idiomatic
      STT_DATA_DIR and serves the UI; required-env-var errors are the same as on Linux
- [ ] A full upload → transcribe → diarize run completes on Windows and its output matches Linux
- [ ] Pausing a running task on Windows stops progress and resume continues from the next chunk
      without corrupting the transcript (per the decided checkpoint design)
- [ ] Cancel and delete leave no orphan processes (whisper-cli/ffmpeg all gone) on Windows
- [ ] Queue pause, reorder, retry and history behave as on Linux
- [ ] Device selection works with the GPU-enabled build (or, on CPU-only CI, the flag path is
      verified and documented as CPU-first)
- [ ] README documents Windows support and the bootstrap path
- [ ] (CI, inherited from 51 on 2026-10-14) This item adds its Windows leg to
      `.github/workflows/ci.yml`; it runs on the nightly schedule and on this item's PRs; no Nix job
      exists on Windows
- [ ] (e2e, CI Windows, moved from item 50 on 2026-10-15) The packaged desktop app runs on Windows
      with its per-user paths: item 50's packaged specs (journey, quit without orphan processes,
      first run with the model download and its persistence, download failure with Retry) pass
      against the installed Windows build
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build` and Linux
      e2e stay green

## Acceptance criteria

- Windows x64 is a documented, tested platform for the runtime and queue semantics; the POSIX-only
  assumptions (setsid, signals, /dev/null, PATH, exit-143) are gone or cleanly abstracted; every
  spawned tool is a pinned Windows artifact (GPU build included); the e2e journey passes on Windows.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed from the Windows portability pass (setsid/signals/143/PATH/NUL/no artifacts).
- 2026-10-13: consultation — checkpoint pause at chunk boundaries; GPU-enabled whisper-cli in scope
  (backend chosen at implementation); verification via virtualization + CI runner (dependency 51);
  delivery `gradlew.bat` + `.cmd` zip. Status: spec-approved.
- 2026-10-15: item 50's test item "The packaged app runs on macOS and Windows with their per-user
  paths" moved here (Windows half) by the user's decision, so item 50 could ship Linux first;
  dependency on 50 added, 50 removed from "Blocks".