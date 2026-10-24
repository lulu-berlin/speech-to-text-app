# 79 — The full e2e journey on Windows

Status: draft
Depends on: [49](../docs/specs/windows-support.md) (in progress; the Windows CI legs, packaged e2e and the
process-control seam), [21](../docs/specs/frontend-backend-end-to-end.md) (done; the journey and its
harness), [51](../docs/specs/ci-verification.md) (done; the CI legs)
Blocks: [78](78-stable-release.md) (a 1.0 gate: Windows is a supported platform)

## Goal

The Playwright journey in `e2e/tests/` (41 spec files) runs on the Windows CI runner as it already
does on Linux and macOS. Today Windows coverage is the backend unit/integration suites plus the
packaged desktop specs against the zip and the MSI; the journey's queue semantics, pause/resume/
cancel, playback, transcript editing, settings, uploads and the HTTP/SSE paths are exercised on
Windows only by hand on the Windows 11 guest.

## Evidence

- The journey is Linux- and macOS-only in CI: `E2E (Playwright)` runs `./gradlew e2e` on Ubuntu
  (`.github/workflows/ci.yml:103,126`) and `macOS (e2e)` runs the same on macOS
  (`.github/workflows/ci.yml:175,184`).
- Item 49 adds the Windows legs — `Windows (gradle check)`, `Windows (pnpm test, pnpm build)` and
  `Windows (packaged zip e2e)` — and the last one runs item 50's four packaged specs plus the MSI
  installer spec: a subset of the journey, against the packaged app rather than the Gradle-managed
  one.
- Item 49's review removed the Windows branch of `e2e`, `e2eInstall` and `e2eBrowsers`: the pnpm
  shim path was wrong and the tasks now fail fast with a pointer to the packaged e2e
  (`build.gradle.kts`'s `requirePosixE2e`, item 49's branch and Approval log, finding 4). This item
  restores the tasks with npm's actual global layout on Windows.
- The harness assumes POSIX in at least one place: `e2e/lib/backend.ts:335` stops the app with
  `process.kill(-pid, 'SIGTERM')`, a process-group kill Windows does not have; the process listing
  (`e2e/lib/processes.ts:19-34`) reads `/proc` or runs `ps -axo`, neither of which exists on
  Windows (item 48 added the macOS path).
- Item 54's type-check and documentation-check tests build their sandboxes from symlinks
  (`symlinkSync` in `e2e/lib/type-check.test.ts:51,55,61`, `docs/type-check.test.ts:35,39` and
  `docs/checks-failures.test.ts:41`). Windows refuses to create a symlink without Developer Mode or
  an elevated shell (`EPERM`), and `e2e/lib/type-check.test.ts` runs in the e2e package's
  `pnpm test`, which `gradlew.bat e2e` would run. Today these tests run only on Linux and macOS
  (item 54's review, 2026-10-24).
- Queue semantics, pause/resume/cancel, the device probe and the GPU path on Windows are currently
  user-verified on the guest, not CI-verified (item 49's Approval log, cycles 5-8).
- Item 49 measured its Windows path at ~12 min; macOS e2e runs 13-16 min, the band item 49 used for
  its cadence decision.

## Proposal

1. **Restore the Windows branch of the Gradle e2e tasks** with npm's global layout
   (`pnpm/pnpm-v<version>/pnpm.cmd`, no `bin/`), and add a `Windows (e2e)` job that runs
   `gradlew.bat e2e` like the Linux and macOS jobs do.
2. **Make the harness portable.** The backend stop uses Windows' own tree kill (`taskkill /T /F`,
   the seam item 49 adds for the desktop) instead of a negative-pid signal; the process listing
   gets a Windows path (for example `Get-CimInstance Win32_Process` or `tasklist`), with the
   existing `/proc` and `ps` paths unchanged; paths, separators and line endings are derived, not
   assumed; the fixture generation already runs in Node (item 69); no Xvfb is needed — Chromium runs
   without a display server.
3. **Gate only what is genuinely POSIX-only**, and make every skip visible in the run (a named
   skip, not a silent pass), listing the gated specs in the test list.
4. **Wire the job** into the Windows leg and `CI (all checks)`'s `needs`, and record its measured
   wall time against the macOS band before deciding whether it runs on every PR or nightly only.

## Open questions

1. **Per-spec gating**: does any journey spec assert POSIX-only behavior (signals, process groups,
   `setsid`, a display server)? Which, and should they be skipped on Windows with a named reason or
   rewritten to the platform-neutral signal?
2. **Cadence**: a Windows e2e job adds ~12 min. Start nightly-only and move to every PR once the
   measured time is comparable to macOS, or every PR from the start?
3. **Which backend**: the journey starts the Gradle-managed backend itself (as `gradle e2e` does on
   Linux and macOS). Is that also the Windows target, with the packaged zip remaining covered by
   the packaged specs, or should the journey run against the unpacked zip?

## Test list (proposed; behaviors only)

- [ ] (CI) `Windows (e2e)` runs the whole journey green on the Windows runner, the same specs as
      `E2E (Playwright)`, and is part of `CI (all checks)`
- [ ] (harness) The journey starts and stops the backend on Windows without a POSIX group kill, and
      a run leaves no orphan JVM, whisper-cli or ffmpeg process
- [ ] (harness) A spec that fails mid-run still stops the backend and leaves no orphan process and a
      readable log
- [ ] (gating) Every spec that cannot run on Windows is named in the test list and skipped with a
      visible reason; no spec is skipped silently
- [ ] (harness) The e2e package's `node --test` suite, including the type-check sandbox, passes on
      the Windows runner without symlink privileges
- [ ] (must not break) The Linux and macOS journeys stay green, with the same spec count
- [ ] (must not break) `gradlew.bat e2e` on Windows fails with a clear message when the managed pnpm
      is missing, instead of skipping or hanging

## e2e stance

This item's deliverable is the e2e coverage itself (ADR 0046); it adds no app feature, so no
additional feature e2e is required — the reason is recorded here as item 65 does for its test-only
scope.

## Acceptance criteria

- The full journey runs on Windows in CI, green, on the agreed cadence, with the same assertions as
  on Linux and macOS except for named, documented platform skips.
- The e2e harness has no unguarded POSIX assumption left; the Linux and macOS paths keep their
  current behavior.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, as a 1.0 gate of roadmap
  item 78. Not yet discussed.
- 2026-10-24: item 54's review added the symlink-based sandboxes to the Evidence and the test list
  (the user's decision). Still a draft.
