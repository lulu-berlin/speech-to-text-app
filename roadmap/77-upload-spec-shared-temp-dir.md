# 77 — `UploadStoredInDatabaseSpec` fails when another process writes to the shared temp dir

Status: spec-approved
Depends on: —
Blocks: —

## Goal

`UploadStoredInDatabaseSpec` (item 44) must fail only when the app writes a file outside the
database during an upload, never because an unrelated process wrote a file to the same temp dir.
Today it watches the whole shared OS temp dir, so `macOS (gradle check)` fails intermittently on
commits whose backend sources pass everywhere else. The fix must not weaken the spec's claim, and a
future failure must name the file that appeared.

## Evidence

- Observed failures (recorded in `docs/known-issues.md` on `roadmap/49-windows-support`,
  2026-10-23): the macOS arm64 runner failed `disk.seen shouldBe emptySet()` on `b884993` (run 183)
  and on `9501915` (run 187, 320 tests, 1 failed); Linux and Windows passed the same commits.
  `macOS (gradle check)` passed on `cb507d4`, `57f5211` and `a4a150a` with the same backend
  sources, and in nine other recent runs (main `7664d42`, item 69's runs).
- The watch covers the whole OS temp dir: `UploadStoredInDatabaseSpec.kt:51` takes
  `java.io.tmpdir`, `:59` builds `DiskWatch(listOf(dataDir, osTemp), …)`, and `DiskWatch` (`:116`)
  snapshots every regular file under its roots at construction, then rescans every 256 KiB of each
  body and once more at the end (`:95`). Any new file under the temp dir that the database does
  not own is recorded, whoever wrote it.
- That temp dir is shared: the Gradle test task sets no `java.io.tmpdir`
  (`backend/build.gradle.kts:115-136`). The test JVM therefore uses the JDK default: the per-user
  `$TMPDIR` on macOS and `/tmp` on Linux. Every process of the same user writes there, the Gradle
  daemon running the build included. `[INFERENCE]`: the JDK defaults, not checked on the runner.
- The app's own use of the temp dir is `ScratchDir` (`backend/src/main/kotlin/stt/db/ScratchDir.kt:23`,
  `java.io.tmpdir/stt-<fingerprint>/`), used for task processing. The spec pauses the queue
  (`:50`), so no task scratch is expected during it.
- CI cannot say which file appeared: Gradle's console prints only
  `AssertionFailedError at UploadStoredInDatabaseSpec.kt:96`. Nothing in `build.gradle.kts`
  configures `testLogging`, and the HTML/XML report that holds the assertion message (the seen
  paths) is not uploaded. So the cause is `[INFERENCE]`: an unrelated writer is the likely one
  (macOS only, not reproducible on the same sources). A rare, real spool by the app on macOS is not
  excluded until a failure names the path.
- Precedent for a private temp dir: `UpgradeCrashScratchIntegrationSpec.kt:81-102` starts the app
  with its own `-Djava.io.tmpdir` (plus `org.sqlite.tmpdir` and `io.netty.native.workdir`).

## Proposal

1. **Name the file on failure.** A failing backend test's assertion message reaches the CI log, so
   the next failure of this spec (or any other) shows the seen paths.
2. **Give the test JVM a private temp dir.** The Gradle test tasks set `java.io.tmpdir` to a
   directory under `backend/build/`, created before the run, that only the test JVM writes to.
   The spec keeps watching the data dir plus the **whole** `java.io.tmpdir`, so a spool written
   anywhere in the app's temp dir (not only in `ScratchDir`) is still caught.
3. **Prove the watch still catches leaks.** A file that appears in a watched dir during an upload,
   even one deleted before the upload ends, still fails the check and is named.

Rejected alternatives:

- Watching only `ScratchDir.dir` and the data dir: it misses a spool the JDK or Ktor writes to the
  temp-dir root, which is what item 44's claim is about.
- Setting `java.io.tmpdir` inside the spec at runtime: the JDK reads the property once and caches
  it for its temp-file helpers `[INFERENCE]`, so an in-process override is unreliable.
- Forking the app into its own JVM, as the upgrade-crash spec does: an Integration-sized harness
  for a unit spec.

## Decided (consultation, 2026-10-23)

- **Every Gradle `Test` task gets the private temp dir** (the user): `test` and `integrationTest`
  under one rule; integration specs use `ScratchDir` too.
- **The full exception format in the console** (the user): the test tasks print a failing test's
  assertion message and trace, so the public CI job log names the seen paths without signing in.
  The test reports are not uploaded as an artifact.
- **20 consecutive green `macOS (gradle check)` runs** (the user) are required in addition to the
  deterministic tests. With the observed failure rate (about 2 in 14 runs), 20 passes in a row
  would happen by chance only about 5% of the time if the flake were still there.

## Open questions (answered 2026-10-23)

- Which test tasks get the private temp dir? → **Every `Test` task** (`test` and
  `integrationTest`).
- How do the paths reach CI? → **The full exception format in the `gradle check` console**.
- What CI evidence closes the flake? → **20 consecutive green `macOS (gradle check)` runs** on
  the item's branch, plus the deterministic tests.

## Test list (approved 2026-10-23; behaviors only)

- [ ] (regression) A file another writer creates in the shared OS temp dir while the 8 MiB upload
      streams does not fail the spec
- [ ] (edge — the spec still catches leaks) A file that appears in the app's temp dir or in the data
      dir during an upload, including one that is gone again before the upload ends, fails the
      check, and the failure names its path
- [ ] (edge) The app under test writes its temp files (its scratch directory) inside the watched
      temp dir, not in the shared OS temp dir, under both `gradle test` and `gradle integrationTest`
- [ ] (diagnosis) A failing backend test's assertion message (for this spec, the seen paths)
      appears in the `gradle check` console output, so a CI job log shows which file appeared
- [ ] (CI) `macOS (gradle check)` passes 20 consecutive runs on the item's branch; a single failure
      restarts the count and its job log names the file that appeared
- [ ] (must not break) The spec's claims are unchanged: no file outside the database during the
      upload, after the success, after the mid-body failure and after the 400 rejection; the stored
      original equals the uploaded bytes
- [ ] (must not break) `gradle check` and `gradle integrationTest` stay green on the Linux, macOS
      and Windows CI legs, the scratch-dir specs included

## e2e stance

The item changes only the backend test harness and the build's test configuration. No app feature
changes, so it adds no feature e2e. ADR 0046's build/packaging/refactor-only exemption does not
literally cover test-only items, so the reason is stated here, as in item 65.

## Acceptance criteria

- `UploadStoredInDatabaseSpec` no longer depends on what other processes write to the OS temp
  dir, and it still catches and names any file the app writes outside the database during an
  upload.
- A failing backend test's assertion message is visible in the CI job log.
- `macOS (gradle check)` passed 20 consecutive runs on the item's branch.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, from the known-issues entry
  recorded on `roadmap/49-windows-support` (`7b52120b`). The entry exists only on that branch;
  item 49's workspace agent removes it there, since the issue is now planned work. Not yet
  discussed.
- 2026-10-23 (consultation, the user): every `Test` task gets the private temp dir; failing tests
  print the full exception format in the console; 20 consecutive green `macOS (gradle check)` runs
  are required. Test list amended with the 20-run item and the `integrationTest` scope, then
  approved as worded. Status: spec-approved.
