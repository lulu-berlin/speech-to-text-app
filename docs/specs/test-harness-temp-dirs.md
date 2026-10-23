# 77 — Private test temp dirs for the backend suite, and a named leak in the upload spec

Status: done (roadmap item 77, approved 2026-10-23)
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

- [x] (regression) A file another writer creates in the shared OS temp dir while the 8 MiB upload
      streams does not fail the spec
- [x] (edge — the spec still catches leaks) A file that appears in the app's temp dir or in the data
      dir during an upload, including one that is gone again before the upload ends, fails the
      check, and the failure names its path
- [x] (edge) The app under test writes its temp files (its scratch directory) inside the watched
      temp dir, not in the shared OS temp dir, under both `gradle test` and `gradle integrationTest`
- [x] (diagnosis) A failing backend test's assertion message (for this spec, the seen paths)
      appears in the `gradle check` console output, so a CI job log shows which file appeared
- [x] (CI) `macOS (gradle check)` passes 20 consecutive runs on the item's branch; a single failure
      restarts the count and its job log names the file that appeared. **Decision 2026-10-23 (the
      user)**: the 20 runs were on `e236ebb1`; for the later test-only commits one green run of the
      PR suffices, and a failure later reopens the item
- [x] (must not break) The spec's claims are unchanged: no file outside the database during the
      upload, after the success, after the mid-body failure and after the 400 rejection; the stored
      original equals the uploaded bytes
- [x] (must not break) `gradle check` and `gradle integrationTest` stay green on the Linux, macOS
      and Windows CI legs, the scratch-dir specs included
- [x] (discovered, 2026-10-23, root review) A watched directory reached through a symlink reports
      no file that existed before the watch started
- [x] (discovered, 2026-10-23, root review) The symlink scenario is skipped where the host cannot
      create a symbolic link (Windows without Developer Mode or elevation), so the suite never fails
      for a privilege it cannot grant

## e2e stance

The item changes only the backend test harness and the build's test configuration. No app feature
changes, so it adds no feature e2e. ADR 0046's build/packaging/refactor-only exemption does not
literally cover test-only items, so the reason is stated here, as in item 65.

## Acceptance criteria

- `UploadStoredInDatabaseSpec` no longer depends on what other processes write to the OS temp
  dir, and it still catches and names any file the app writes outside the database during an
  upload.
- A failing backend test's assertion message is visible in the CI job log.
- `macOS (gradle check)` passed 20 consecutive runs on the item's branch (`e236ebb1`); the later
  test-only commits are accepted on one green PR run (the user, 2026-10-23).
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
- 2026-10-23 (cycle 1, regression item, confirmed by the user): new scenario in
  `UploadStoredInDatabaseSpec`: after 1 MiB of an 8 MiB body, a writer creates a file in every
  shared OS temp dir found from the environment (`TMPDIR`, `TMP`, `TEMP`, `/tmp` on Unix), and the
  watch over the data dir plus the whole `java.io.tmpdir` must stay empty. Red:
  `AssertionFailedError: expected:<[]> but was:<[/tmp/other-writer-3433232237998619780.tmp]>` at
  `UploadStoredInDatabaseSpec.kt:146`. Green: every Gradle `Test` task sets `java.io.tmpdir` to
  `backend/build/test-tmp/<task>/`, recreated empty before the run; `gradle check` 342 tests,
  0 failures, ktlint clean; `gradle integrationTest` (Linux sandbox) 0 failures, 0 errors.
  Commit `5135a552`.
- 2026-10-23 (cycle 1 refactor checkpoint, the user): approved two refactors, each its own commit:
  one file-level upload helper shared by both scenarios (`5c4e346f`), and `osTemp` renamed to
  `appTemp` with the spec's KDoc saying "the app's temp dir" (`Refactor: name the watched temp dir
  the app's temp dir in the upload spec`). Declined: sharing the byte counting of `ActingAfter` and
  `DiskWatch.watching`. `gradle check` 342 tests, 0 failures after each.
- 2026-10-23 (cycle 2, leak-edge item, confirmed by the user): new scenario in
  `UploadStoredInDatabaseSpec`: mid-upload the app stand-in writes `spool-leak.part` into the data
  dir at 1 MiB + 128 KiB and deletes it 64 KiB later, between two periodic checks and before the
  upload ends; the check must fail and name that path. Red (the new post-write check removed):
  `AssertionFailedError: expected:<[…/build/test-tmp/test/stt-data4245288519999614436/spool-leak.part]>
  but was:<[]>`. Green: `DiskWatch` remembers every recorded path (a file that is gone again still
  counts and is named), rolls each scan into its baseline, and checks right after a mid-upload
  write; paths are normalized with `toRealPath()`, because on macOS `/tmp` is a symlink to
  `/private/tmp`. `gradle check` 343 tests, 0 failures, ktlint clean. `[INFERENCE]`: the macOS
  symlink, not checked on a runner.
- 2026-10-23 (cycle 3, first half of the scratch-dir item, `gradle test`, confirmed by the user):
  new `stt.TestTempDirSpec`: the app's `ScratchDir` parent is the test JVM's `java.io.tmpdir`,
  which is none of the shared OS temp dirs named by the environment. Green on the first run
  (cycle 1's build rule already in place); red recorded by removing that rule for one run:
  `AssertionFailedError: Collection should not contain element /tmp based on object equality, but
  it did at index(es):[0]`. Rule restored (no diff); `gradle check` 344 tests, 0 failures, ktlint
  clean. The item stays unticked until its `gradle integrationTest` half is green.
  Commit `35710e97`.
- 2026-10-23 (cycle 3 refactor checkpoint, the user): approved two refactors, each its own commit:
  `sharedOsTempDirs()` moved to one `internal` function in `stt/SharedTempDirs.kt` (`ffca16ce`),
  and the watch's `writeAt`/`write` hook dropped in favor of `ActingAfter` alone — scenario 3 nests
  one `ActingAfter` that writes the file and calls `disk.check()` inside one that deletes it
  (`Refactor: trigger the mid-upload write with ActingAfter alone`). `gradle check` 344 tests,
  0 failures after each; with the action's `disk.check()` removed, scenario 3 still fails with
  the path expected and `seen` empty.
- 2026-10-23 (cycle 4, second half of the scratch-dir item, `gradle integrationTest`, confirmed by
  the user): new `stt.TestTempDirIntegrationSpec`, tagged `Integration` at spec level (the repo's
  convention; no spec mixes tagged and untagged tests), with the same assertions as
  `TestTempDirSpec` plus the task's own folder: `java.io.tmpdir` ends in `integrationTest`;
  `TestTempDirSpec` now also asserts it ends in `test`, so each task proves its own private dir.
  Green on the first run; red recorded by removing the build rule for one run:
  `AssertionFailedError: expected:<integrationTest> but was:<tmp>`. Rule restored (no diff);
  `gradle check` 345 tests, 0 failures, ktlint clean; `gradle integrationTest` 392 tests,
  0 failures, 0 errors (Linux sandbox). Item ticked.
- 2026-10-23 (cycle 5, diagnosis item, confirmed by the user; the proof shape chosen with the
  user as "nested fixture build"): the full exception format lives in
  `backend/gradle/test-logging.gradle.kts`, which `build.gradle.kts` applies to every `Test` task;
  `TestLoggingIntegrationSpec` (Integration) runs a fixture build that applies the same script to a
  `Test` task running the failing `TestLoggingProbe`, and asserts the build's console output
  carries the probe's message, which names `PROBE_PATH`. Red: the script missing (fixture's
  "Could not read script"), then, with `exceptionFormat` removed, the fixture printed
  `org.opentest4j.AssertionFailedError at TestLoggingProbe.` — the exact CI symptom in the
  Evidence. Green: `gradle check` 347 tests, `gradle integrationTest` 394 tests, 0 failures,
  ktlint clean. Item ticked.
  - (discovered) Gradle TestKit cannot carry the proof: `testImplementation(gradleTestKit())` put
    Gradle's own SLF4J provider on the test classpath and 17 log-capture specs failed with
    `class org.gradle.internal.logging.slf4j.OutputEventListenerBackedLogger cannot be cast to
    class ch.qos.logback.classic.Logger`. The fixture build is started instead with the running
    build's own distribution (`gradle.gradleHomeDir`, passed as `stt.test.gradleHome`) via
    `ProcessRunner`, `--no-daemon`, 5 min timeout — the `ClusteringHeapIntegrationSpec` pattern.
  - (discovered) The Nix Gradle package exposes `libexec/gradle/bin/gradlew`, not `bin/gradle`, so
    the spec picks the first launcher that exists (`gradle`/`gradlew`, `.bat` on Windows).
  - (discovered) Changing a script in `gradle/` does not make a `Test` task out of date: the
    gates were re-run with `--rerun-tasks` after the mutation experiments.
- 2026-10-23 (item 6, must-not-break, no new test): the item's claims are scenario 1 of
  `UploadStoredInDatabaseSpec`, which is unchanged by this item and green in the last
  `gradle check` (3 scenarios, 0 failures): the 8 MiB upload is `201`, the mid-body failure and the
  6 MiB `.txt` rejection (`400`) leave `disk.seen` empty, `tasks` holds one row, and the stored
  original equals the uploaded bytes by SHA-256 and length. A second test of the same assertions
  would duplicate them, so the item is verified by that run. Item ticked.
- 2026-10-23 (item 5, CI count, decided with the user): a run counts when its
  `macOS (gradle check)` job passes, whatever the workflow's other jobs do; a failing
  `macOS (gradle check)` restarts the count. Runs on the item's branch, commit `e236ebb1`:
  - 2026-10-23, run 39960216036 (#201): `macOS (gradle check)` success, workflow success — 1/20
  - 2026-10-23, run 39960472994 (#202): `macOS (gradle check)` success, workflow success — 2/20
  - 2026-10-23, run 39964423404 (#205): `macOS (gradle check)` success (3:24), workflow failure
    (3/20 under the rule above). The only failing job is `macOS (e2e)`:
    `06-playback` expected `00:01 / 02:12`, got `00:02 / 02:12` at
    `e2e/tests/06-playback.spec.ts:92` after its fixed `waitForTimeout(1_500)` — the timing
    assumption already in `docs/known-issues.md:47`, unrelated to this item.
  - 2026-10-23, run 39970073195 (#209): `macOS (gradle check)` success, workflow success — 4/20
  - 2026-10-23, run 39973349475 (#210): `macOS (gradle check)` success, workflow success — 5/20
  - 2026-10-23, run 39976046584 (#212): `macOS (gradle check)` success (2:46), workflow failure —
    `Desktop (packaged .deb e2e)` failed at its "Run the packaged desktop e2e" step — 6/20
  - 2026-10-23, run 39978912415 (#216): `macOS (gradle check)` success (2:37), workflow failure —
    `E2E (Playwright)` failed in `gradle e2e` — 7/20
  - 2026-10-23, run 39982214807 (#221): `macOS (gradle check)` success, workflow success — 8/20
  - 2026-10-23, run 39985914630 (#224): `macOS (gradle check)` success (2:26), workflow failure —
    `E2E (Playwright)` failed in `gradle e2e` — 9/20
  - 2026-10-23, run 39988628993 (#225): `macOS (gradle check)` success, workflow success — 10/20
  - 2026-10-23, run 39991027273 (#227): `macOS (gradle check)` success, workflow success — 11/20
  - 2026-10-23, run 39993408888 (#229): `macOS (gradle check)` success, workflow success — 12/20
  - 2026-10-23, run 39996510177 (#231): `macOS (gradle check)` success (2:25), workflow failure —
    `macOS (e2e)` failed in `gradle e2e` — 13/20
  - 2026-10-23, run 39999401568 (#232): `macOS (gradle check)` success, workflow success — 14/20
  - 2026-10-23, run 40001650440 (#234): `macOS (gradle check)` success (2:20), workflow failure —
    `E2E (Playwright)` failed in `gradle e2e` — 15/20
  - 2026-10-23, run 40004496861 (#236): `macOS (gradle check)` success (2:31), workflow failure —
    **`macOS (gradle integrationTest)` failed** (job 120598152468, `gradle integrationTest` step,
    3:52, `394 tests completed, 1 failed, 321 skipped`) and `macOS (e2e)` failed. The job log names
    `stt.process.ProcessRunnerSpec` ("a pause() issued while the process is
    being spawned … produces no output until resume()"): `AssertionFailedError: List should be
    empty but has 3 elements, first being: "1"` — the SIGSTOP landed after the 0.5 s head start
    (`ProcessRunnerSpec.kt:516`, `pausedFromSpawn` at `:712`). Not this item's specs: Kotest runs
    specs sequentially here (no parallelism config), `ProcessRunnerSpec` uses only `sh -c` loops and
    touches neither `java.io.tmpdir` nor `ScratchDir`; the same wall-clock class is
    `docs/known-issues.md:40` (`ProcessRunnerSpec.kt:376`). Failed once in 18 runs. — 16/20
  - 2026-10-23, run 40006922897 (#237): `macOS (gradle check)` success, workflow success — 17/20
  - 2026-10-23, run 40009910649 (#241): `macOS (gradle check)` success (2:51), workflow failure —
    `E2E (Playwright)` failed in `gradle e2e` — 18/20
  - 2026-10-23, run 40013613365 (#243): `macOS (gradle check)` success, workflow success — 19/20
  - 2026-10-23, run 40015775560 (#244): **cancelled** after 9m3s, so no `macOS (gradle check)`
    result. Treated as "no run", not as a failure: the count stays at 19 and one more dispatch is
    needed. `#236` attempt 2 (the re-run of its failed jobs) was still in progress at this point.
  - 2026-10-23, run 40004496861 (#236) attempt 2, the re-run of its failed jobs:
    `macOS (gradle integrationTest)` **success** (3:48) — the `ProcessRunnerSpec` flake did not
    recur — and `macOS (e2e)` **success** (14:21), so the `06-playback` failure did not recur
    either: attempt 2 is fully green. This closes item 7's macOS integrationTest leg.
  - 2026-10-23, run 40019575088 (#245): `macOS (gradle check)` **success** (2:39) — the twentieth
    green run, all on `e236ebb1`. Count closed at **20/20** (19 consecutive passes, `#244` cancelled
    and treated as no run, then `#245`). Item 5 ticked and the item set to `in-review`.
    `macOS (gradle check)` never failed in any of the 20 runs, so no run had to name a file.
  - Item 7 evidence from the same runs (Linux and macOS legs, commit `e236ebb1`):
    `Backend (gradle check)`, `Backend (gradle integrationTest)`, `macOS (gradle check)` and
    `macOS (gradle integrationTest)` succeeded in 17 of the 18 completed runs and in #236's re-run
    (attempt 2, above). `[INFERENCE]`: that job runs every `Integration`-tagged spec
    (`kotest.tags=Integration`), so the scratch-dir specs (`ProcessingEngine*ScratchSpec`,
    `ClusteringHeapIntegrationSpec`) are among its 392 tests; the job log was not opened test by
    test. The workflow has no Windows legs yet (item 49).
  - The e2e failures in these runs are pre-existing specs, none touched by this item: `#216`, `#224`,
    `#234`, `#241` failed `tests/59-row-color.spec.ts:47` (the `Chunking…` poll, 120 s) and `#216`
    also `tests/59-row-bars.spec.ts:38` (two bars while diarizing); `#205` and `#231` failed
    `tests/06-playback.spec.ts:92` (the paused `mm:ss` text); `#212` failed
    `desktop-tests/13-second-quit-during-shutdown.spec.ts:33` (the `finally` `TypeError`, already in
    `docs/known-issues.md`). `#236`'s `macOS (e2e)` (attempt 1, job 120598152431) is also
    `tests/06-playback.spec.ts:92`, so that spec failed 3 of 20 runs. Entries drafted for
    `docs/known-issues.md`.
  - Item 7's Windows legs (decided with the user, 2026-10-23): this tree's `ci.yml` has no Windows
    jobs (they follow with item 49), and 20 more dispatches just for Windows would cost hours of CI
    for little gain. The item is accepted on the Linux and macOS legs as they are; if a failure
    shows up only on Windows once item 49 lands, it is reopened then. The same applies to the
    `macOS (gradle integrationTest)` flake in #236: it is a known pre-existing wall-clock spec, not
    this item's; it is recorded above and the item does not wait on a re-run.
- 2026-10-23 (cycle 6, root review finding 1, MED, confirmed by the user): new scenario "a watch
  over a directory reached through a symlink": a directory holding `old.txt` is watched through a
  `Files.createSymbolicLink` to it, `check()` must report no file. Red:
  `AssertionFailedError: expected:<[]> but was:<[…/build/test-tmp/test/stt-real…/old.txt]>` — the
  baseline used the constructor parameter `roots` (unresolved), while `check()` scans the
  `toRealPath()` property, so through a link every pre-existing file looked new. Green:
  `before` now reads `this.roots`. `gradle check` 348 tests, `gradle integrationTest` 395 tests,
  0 failures, ktlint clean. Discovered test-list item ticked.
- 2026-10-23 (root review findings 2-4, confirmed by the user): two refactors, each its own commit,
  and one cycle.
  - `Refactor: resolve the allowed database files against the real data dir once`: `databaseFiles()`
    resolves the three names against `dataDir.toRealPath()`, and `DiskWatch` takes `allowed` as
    given, so it no longer calls `toRealPath()` on `-wal`/`-shm` files that may not exist.
  - `Refactor: drop the redundant get:JvmName on DiskWatch.seen`.
  - (cycle 7) The leak scenario now plants `spool-leak.part` in the data dir and `stt-spool-leak.part`
    in the app's temp dir at 1 MiB + 128 KiB, deletes both 64 KiB later, and asserts `seen` holds
    both paths — the wording of test-list item 2 ("in the app's temp dir or in the data dir") is now
    covered by a scenario. Red (mutation: the app temp dir left out of the watch's roots): expected
    both paths, `seen` held only the data dir's. Green: `gradle check` 348 tests,
    `gradle integrationTest` 395 tests, 0 failures, ktlint clean; `UploadStoredInDatabaseSpec` 4/4.
- 2026-10-23 (root review finding 5, MED, confirmed by the user): the symlink scenario is a unit
  spec, so once item 49's Windows legs land it runs on `Windows (gradle check)`, where
  `Files.createSymbolicLink` needs `SeCreateSymbolicLinkPrivilege`. `symlinksUsable` probes the host
  once and the scenario is `config(enabled = symlinksUsable)`, so a host that refuses reports it as
  skipped instead of failing. `UploadStoredInDatabaseSpec` 4/4, symlink scenario enabled here.
  Committed by the root agent during PR prep.
- 2026-10-23 (PR prep, the user): the CI evidence is accepted as is — the 20 green
  `macOS (gradle check)` runs on `e236ebb1` plus one green run of the PR for the four later
  test-only commits; a failure later reopens the item. Accepted, and the finished state prepared:
  spec moved to `docs/specs/test-harness-temp-dirs.md`, roadmap row and graph node updated, item 78
  repointed.
