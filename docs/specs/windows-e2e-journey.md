# 79 — The full e2e journey on Windows

Status: done (roadmap item 79, approved 2026-10-29)
Depends on: [49](windows-support.md) (done; the Windows CI legs, packaged e2e and the
process-control seam), [21](frontend-backend-end-to-end.md) (done; the journey and its
harness), [51](ci-verification.md) (done; the CI legs)
Blocks: [78](../../roadmap/78-stable-release.md) (a 1.0 gate: Windows is a supported platform)

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

## Decided (consultation, 2026-10-28)

- **Nightly first**: the job starts in the nightly workflow, its wall time is measured against the
  macOS 13–16 min band, and it joins the every-PR legs (`CI (all checks)`) only once comparable.
- **The journey starts the Gradle-managed backend**, as `gradle e2e` does on Linux and macOS; the
  packaged zip stays covered by `Windows (packaged zip e2e)`.
- **POSIX-only specs are audited one by one**: each is rewritten to a platform-neutral assertion
  where that is cheap, otherwise skipped with a named reason listed in this item's test list; no
  spec is skipped silently.

## Test list (approved; behaviors only)

- [x] (CI) `Windows (e2e)` runs the whole journey green on the Windows runner with the same specs as
      `E2E (Playwright)`; it starts nightly and joins `CI (all checks)` once its measured time is
      comparable to the macOS band (run #389 at `4acc6ff`: green, 15:54 against macOS 15:32 and
      Linux 14:06; therefore now changes-gated and in the aggregator)
- [x] (harness) The journey starts and stops the backend on Windows without a POSIX group kill, and
      a run leaves no orphan JVM, whisper-cli or ffmpeg process
- [x] (harness) A spec that fails mid-run still stops the backend and leaves no orphan process and a
      readable log
- [x] (gating) Every spec that cannot run on Windows is named in the test list and skipped with a
      visible reason; no spec is skipped silently
- [x] (harness) The e2e package's `node --test` suite, including the type-check sandbox, passes on
      the Windows runner without symlink privileges
- [x] (must not break) The Linux and macOS journeys stay green, with the same spec count
      (dispatch run #388 at `7d2b456`: `Linux (e2e)` and `macOS (e2e)` green, user-reported; 46 spec
      files on `main` and on the branch, only `60-device-probe.spec.ts` changed, no new skip)
- [x] (must not break) `gradlew.bat e2e` on Windows fails with a clear message when the managed pnpm
      is missing, instead of skipping or hanging
- [x] (discovered) (build) The e2e Gradle tasks start the managed Node by its absolute path, so
      `./gradlew e2e` and `gradlew.bat e2e` work on a host with no Node on PATH (review finding 1:
      Gradle's Exec resolves the program on Gradle's own PATH, so the bare `node` the tasks passed
      bypassed the managed install)
- [x] (discovered) (harness) The launcher starts a managed pnpm path that contains spaces, so
      `C:\Users\John Doe\.gradle\stt-app\…` starts the suite (review finding 2: the shell received
      the unquoted `.cmd` path)
- [x] (discovered) (harness) A start that fails stops the health polling, so nothing keeps polling a
      dead backend for the rest of the 600 s timeout (review finding 6)
- [x] (discovered) (harness) The device-probe spec finds whisper-cli on Windows: today
      `tests/60-device-probe.spec.ts` runs `execFileSync('which', ['whisper-cli'])` and splits PATH
      on `':'`, neither of which exists there; it must locate the executable by `path.delimiter`
      and PATHEXT so the spec runs on Windows instead of failing
- [x] (discovered) (harness) The journey starts the Gradle-built backend's `stt-backend.bat` on
      Windows: `launchBackend` spawns `E2E_BACKEND_CMD` without a shell, and Node refuses to start a
      `.bat`/`.cmd` that way since its 2024 security fix (`EINVAL`), so global setup would fail
      before the first spec
- [x] (discovered) (backend build) The backend's Windows start script runs at any checkout depth:
      the generated `stt-backend.bat` lists every jar as `%APP_HOME%\lib\<jar>` on one
      `set CLASSPATH=` line, which expands to ~9,075 characters on the runner — over cmd.exe's 8,191
      limit ("The input line is too long") — so neither the journey nor `gradlew.bat run` starts
      from a deep path; the script names the jars by Java's classpath wildcard instead
      (the user's decision, 2026-10-29)
- [x] (discovered) (harness) Global setup fails as soon as the backend process exits, naming its
      exit, instead of waiting the full 600 s for a health answer that cannot come (run #388 sat
      idle 10 minutes on a dead backend)
- [x] (discovered) (harness) A managed pnpm path that contains cmd.exe special characters (`&`, `|`,
      `^`, `(`, `)`) starts the suite on Windows (review finding: the launcher quoted only values with
      whitespace, and cmd.exe reads those characters as syntax outside quotes, so a profile path like
      `C:\Users\R&D\…` still broke the launch)

Gated specs (audit of all 46 specs, 2026-10-29): `18-macos-metal.spec.ts` — "Metal acceleration is a
macOS-runner capability"; it skips on Windows and Linux with that reason, and no other spec skips.
`lib/platform-gates.test.ts` fails on a skip with no reason and prints every skip it finds
(`platform skips: […]`) in the run's output; a named skip passes, so this paragraph is the record of
the gates, and the check guarantees a reason exists, not that this list is complete.

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
- 2026-10-28: consultation with the user: the job starts nightly and joins the every-PR legs once
  its time is comparable to macOS; the journey starts the Gradle-managed backend; POSIX-only specs
  are rewritten where cheap and otherwise skipped with a named reason. Test list approved as
  written; status set to spec-approved.
- 2026-10-29 (workspace, test-list item 1): the user's rule — no cron starts on minute :00, the
  busiest minute, when GitHub delays or drops scheduled runs. The nightly moved from 03:00 to 03:17
  and the weekly model-host cron from 04:00 to 04:43 (Europe/Berlin), with the jobs gated on those
  exact strings (`windows-e2e`, `model-hosts`) and the pinned string in
  `docs/check-ci-gating.test.ts` moved with them.
- 2026-10-29 (workspace, test-list item 1): dispatch run #389 at `4acc6ff` finished green with
  `Windows (e2e)` at 15:54 (macOS 15:32, Linux 14:06; the agreed macOS band is 13–16 min). Because
  the time is comparable, the job left the event-gated exemption, became changes-gated like the
  other journeys and joined `CI (all checks)`; the test list is complete and the item is in review.
- 2026-10-29 (workspace, review fixes): the root agent's review raised six findings; all are fixed.
  Findings 1, 2 and 6 became test-list items and TDD cycles: the e2e Gradle tasks start the managed Node
  by its absolute path (registered as the build's one `nodeExe`, which also puts it on the tasks' PATH);
  the launcher quotes a managed pnpm path that holds spaces; a failed start cancels the health wait.
  Findings 3-5 were documentation: the README's Windows section now documents `gradlew.bat e2e`, the docs
  package's symlink sandboxes are filed as a LOW known issue, and the "Gated specs" paragraph says what
  the gate actually enforces. One discovered item was recorded — node-gradle runs npm through its
  `#!/usr/bin/env node` shebang, so a Node-less host still fails in `:frontend:pnpmSetup` — and later
  dropped as invalid (2026-10-29, see below).
  Verified on Linux: e2e `node --test` 16/16, prettier, tsc, `gradle -p backend check`, `TestLoggingIntegrationSpec`
  (nested Gradle) green while `E2eTasksManagedNodeIntegrationSpec` skipped, and the full journey (54 passed,
  1 named skip). Windows: `Windows (e2e)` to re-run.
- 2026-10-29 (workspace, discovered-item cleanup): that no-Node-on-PATH item was based on a false
  premise and the user directed its removal. node-gradle 7.1.0 does supply the managed Node to the
  child: `computeAdditionalBinPath` returns `$nodeDir/bin` when `download = true`, and `computeEnvironment`
  prepends the additional bin paths to the child's `PATH`. A probe confirmed it on Linux — with the host
  PATH stripped of Node and `-x :frontend:nodeSetup`, the command node-gradle started as `…/bin/npm`
  received `…/nodejs/node-v24.21.0-linux-x64/bin` as the first two `PATH` entries. The ENOENT that looked
  like a gap is this sandbox lacking `/usr/bin/env` altogether (only the Nix `env` exists), so the kernel
  cannot start the shebang script; every supported host — Ubuntu runner, macOS, NixOS, and Windows,
  where npm is `npm.cmd` — has the interpreter the shebang names. No code changed.
- 2026-10-29 (workspace, third review): the root agent's three points are closed. Point 1: the spec now runs
  `:frontend:nodeSetup` before deciding to skip and, on success, the whole `gradle e2e` chain with
  `:frontend:pnpmSetup` included (excluded only where `/usr/bin/env` is missing); it skipped after the download
  on both hosts available here — `tests="1" skipped="1"`, 10.7 s here and 35.5 s on the user's host, with the
  printed reason "the managed Node exists at …/bin/node but cannot execute on this host" (no `/lib64` loader for
  node-gradle's glibc download). By the user's decision the chain run is left to the CI legs Linux (gradle
  integrationTest) and macOS (gradle integrationTest), which run on glibc/macOS hosts. The other
  nested-Gradle spec, `TestLoggingIntegrationSpec`, ran and passed. Point 2: cmd.exe's `&`, `|`, `^`, `(` and `)`
  are quoted (program always quoted on win32); test-list item added, red then green, and ticked. Point 3: the
  known-issues row has its verb again. Skip reasons now also print into the run's `<system-out>`. Verified here:
  e2e `node --test` 17/17, prettier, tsc, the docs package (30/30), `gradle -p backend check` and
  `gradle integrationTest` green with 0 failures. Windows behavior and `Windows (e2e)` remain unverified here.
