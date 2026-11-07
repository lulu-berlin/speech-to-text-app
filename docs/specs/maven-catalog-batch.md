# 93 — Current Maven dependencies: the catalog batch and the regenerated deps.json

Status: done (roadmap item 93, approved 2026-11-07)
Depends on: [03](database-schema.md) (done; the migrations), [08](speaker-embeddings.md) (done; the ONNX Runtime pin and item 30's regression history)
Blocks: —

## Goal

The backend builds on current Maven dependencies — Ktor 3.6.1, Kotest 6.2.6, Logback 1.6.6, Exposed
1.6.0, Flyway 13.12.0, sqlite-jdbc 3.54.1.0, JNA 5.19.1, ONNX Runtime 1.31.1 — with the same
behaviour, `backend/deps.json` regenerated, and Kotlin/kotlinx-serialization still on Ktor's baseline.

## Evidence

- **The catalog** (`backend/gradle/libs.versions.toml`): Kotlin 2.3.21, Ktor 3.6.0, Kotest 6.2.5,
  Logback 1.6.5, kotlinx-coroutines 1.11.0, Exposed 1.5.0, Flyway 13.9.0, sqlite-jdbc 3.50.3.0, JNA
  5.14.0, ONNX Runtime 1.31.0. Latest on Maven Central (checked 2026-11-04): Ktor **3.6.1**, Kotest
  **6.2.6**, Logback **1.6.6**, Exposed **1.6.0**, Flyway **13.12.0**, sqlite-jdbc **3.54.1.0**, JNA
  **5.19.1**, ONNX Runtime **1.31.1**; coroutines 1.11.0 and ktlint 1.8.0 are current.
- **Kotlin stays paired with Ktor**: stable Kotlin is 2.4.21 and kotlinx-serialization 1.12.0, but
  the catalog's comment pins the pair to what Ktor is built against ("Ktor 3.6.0 is built against
  Kotlin 2.3.21 and kotlinx-serialization 1.11.0; keep the pair when bumping"). Ktor 3.6.1 is still
  the 2.3 line, so Kotlin does not move independently.
- **ONNX Runtime carries item 30's history**: 1.29 and 1.30 segfaulted on one machine
  (CPU-dispatched recursion), 1.26–1.28 degraded CAM++ embeddings everywhere; 1.31.0 is the next
  release on the safe line, and 1.31.1 is a patch on it that must pass the same integration
  evidence. sqlite-jdbc and onnxruntime are force-pinned in `backend/build.gradle.kts:40-46`
  (ADR 0042).
- **`backend/deps.json` is the Nix Gradle lock**: a dependency change regenerates it with
  `nix build .#backend.mitmCache.updateScript --print-out-paths` and the printed script
  (`flake.nix`'s comment), and a stale lock reds the Nix build.
- **The suites that judge it**: `gradle check`, the Integration-tagged suites (real processes,
  migrations on a real SQLite file, ONNX diarization) and the e2e journeys.

## Decided (consultation, 2026-11-04)

- Filed at the user's request from the dependency scan: the catalog batch in one item, because the
  versions pair (Kotlin/serialization) and `deps.json` make piecemeal bumps more expensive than one.
- The forced pins (ADR 0042) stay forced; their versions move, not the mechanism.
- **Exempt from ADR 0046's per-feature e2e test** (the user): no app feature changes; the existing
  journeys and the Integration suites running on the new versions are the proof.

## Proposal

1. Bump the catalog entries listed above; leave Kotlin, kotlinx-serialization and ktlint where they
   are. Confirm Ktor 3.6.1's expected Kotlin/serialization pair from its POM before starting.
2. Fix whatever API changes the minors require (Exposed, Flyway, sqlite-jdbc, JNA), keeping the
   schema, the migrations and the queries as they are. Two checks first, from the release notes:
   whether Exposed 1.6.0's changes reach the DSL/DAO surface the backend uses, and whether Flyway
   13.10–13.12 changes `baseline`/`validate` behaviour for existing `stt.db` files.
3. Re-run `gradle check`, the integration suites and the e2e journeys; re-run the ONNX Runtime
   regression scenarios item 30 records (the crash and the degraded embeddings).
4. Regenerate `backend/deps.json`, then verify `nix build .` (user-run) and `checkVersions`.
5. Update the catalog's comments (the Ktor pair and ADR 0042's forced pins) where versions moved.

## Open questions

None. (Settled 2026-11-04: the Exposed and Flyway questions are facts to check first, proposal step
2.)

## Test list

- [x] (basic) `gradle check` and the Integration-tagged suites pass on the new versions.
- [x] (basic) The migrations apply to a fresh database and to an existing one, and the queue and
      history behaviour is unchanged.
- [x] (basic) SQLite reads and writes go through the new sqlite-jdbc under ADR 0042's forced pin, and
      only one sqlite-jdbc version is on the classpath.
- [x] (basic) Diarization yields the same speaker turns on ONNX Runtime 1.31.1: the item-30
      regression scenarios (the crash and the degraded-embedding case) stay green.
      _(both halves green: embedding suites on the real CAM++ model in the sandbox (cycle 2) and two
      consecutive COMPLETED runs with speaker turns on the user's machine, 2026-11-07)_
- [x] (edge) Ktor 3.6.1's server, DI, content negotiation and SSE endpoints behave as their specs
      say: an upload error and a stream drop keep their current handling.
- [x] (edge) Logback's output pattern and levels are unchanged, and Kotest's tags still split the
      unit and Integration suites.
- [x] (must not break) Kotlin and kotlinx-serialization stay on Ktor's pair, and ktlint stays at its
      current version.
- [x] (must not break) `backend/deps.json` is regenerated, and `nix build .` (user-run),
      `checkVersions` and the e2e journeys are green with it.
      _(journeys 57 passed in the sandbox; the two app-mode service specs cannot spawn a `/bin/sh`
      launcher in this container and need CI or a normal OS — cycle 8)_

## e2e stance

Exempt from ADR 0046's per-feature e2e test (decided 2026-11-04): no app feature changes, and the
existing journeys and Integration suites running on the new versions are its proof.

## Approval log

- 2026-11-04: filed as a draft by the root agent from the dependency scan (Maven Central, the eight
  catalog entries behind their latest release).
- 2026-11-04: consultation with the user: the Exposed/Flyway questions become checks in proposal
  step 2; exempt from ADR 0046. Test list approved as written; status set to spec-approved.
- 2026-11-06 (workspace, before cycle 1; proposal steps 1–2, the facts checked first):
  - **Ktor 3.6.1's pair is unchanged**: the Maven Central POMs of `io.ktor:ktor-server-core-jvm:3.6.1`
    and `io.ktor:ktor-serialization-kotlinx-json-jvm:3.6.1` declare `kotlin-stdlib` 2.3.21,
    `kotlinx-serialization-json-jvm` 1.11.0 and `kotlinx-coroutines-core-jvm` 1.11.0 — the catalog's
    Kotlin 2.3.21 and coroutines 1.11.0 stay, and serialization (not a catalog entry; it arrives
    through `ktor-serialization-kotlinx-json`) stays 1.11.0. No other new artifact pulls the stdlib
    above 2.3.21: Exposed 1.6.0 declares 2.3.20, Kotest 6.2.6 declares 2.2.21 (POMs).
  - **Exposed 1.6.0 against the backend's surface** (source: the `CHANGELOG.md` `# 1.6.0` section in
    JetBrains/Exposed; the surface from the imports under `backend/src`): the backend uses the JDBC DSL
    only — `Table`, `selectAll`/`select`, `insert`, `batchInsert`, `upsert`, `update`, `deleteWhere`,
    `transaction`, `ExposedBlob`, `CustomFunction("length", …)`, `GlobalStatementInterceptor` — no DAO,
    no R2DBC, no `SchemaUtils`, no column `transform`, no Exposed-side defaults (schema is Flyway's).
    1.6.0's API changes are additive (nullable `transform` to non-null, R2DBC DAO, multi-package
    migration plugin); none touches a signature the backend calls. The behavioural changes that can
    reach it: EXPOSED-1071 (closing executed statements), the generated-keys performance change
    (`insert` returning ids), EXPOSED-1088 (batch-insert defaults; the backend's tables declare none)
    and the Version-class dialect comparisons (SQLite feature checks) — all judged by the repository
    suites, not by code changes. 1.5.1's CVE-2026-108474 fix (string arguments of `locate`/`concat`/
    `groupConcat`/JSON functions) predates 1.6.0 (Central: 1.5.1 2026-10-09, 1.6.0 2026-10-16); the
    backend calls none of them.
  - **Flyway 13.10–13.12 and `baseline`/`validate`** (source: Redgate's "Release Notes for Flyway
    Engine", sections 13.10.0, 13.10.1, 13.11.0, 13.11.1, 13.12.0): no change to `validate` or to
    `migrate`'s validate-on-migrate. The baseline-related entries do not apply: 13.10.1 issue 4292
    (a `baseline` script shown `Pending`) is Native Connectors mode only, and 13.11.0 makes `info`
    honour `baselineOnMigrate` — the backend calls only `migrate()` through the Java API with the
    defaults (no `baselineOnMigrate`, no `info`). 13.12.0's database item is H2-only; its
    `jackson-annotations` becomes optional in `flyway-core` (still reached through
    `flyway-database-nc-sqlite`'s `jackson-databind`). So an existing `stt.db` with its
    `flyway_schema_history` is validated and migrated as on 13.9.0; test-list item 2 checks it.
    Central also has 13.10.2/13.10.3 with no release-note section of their own.
  - **Flyway 13.12.0 still declares sqlite-jdbc 3.50.3.0** (`flyway-parent-13.12.0.pom`,
    `version.sqlite`), below the new pin: ADR 0042's force now holds the driver *up* against a
    transitive, where it used to hold it at the catalog against a higher one.
  - **Newer than the approved scope, not taken**: Exposed 1.6.1 (Central 2026-11-04) and Flyway 13.12.1
    (2026-11-05) were released after the item's version check; the scope stays 1.6.0 and 13.12.0.
  - Release-note sources for the edge items, recorded now: Ktor 3.6.1 (GitHub release, published
    2026-10-29) lists KTOR-9430 (DI resources cleaned up under `testApplication`), KTOR-10023
    (`BadRequestException` for malformed paths, a 3.6.0 regression), KTOR-9790 (`sseSession()` with a
    heartbeat) and KTOR-9968 (test-engine streaming over 1 MB) as the entries near the backend's
    surface (test-list item 5).
  - Baseline at `3752a360` (old versions, before any bump): `gradle check` green — ktlintCheck, 426
    tests, 0 failures, 1 skipped, 152 specs.

- 2026-11-07: reviewed by the root agent and approved by the user. The review checked every pin
  against the catalog and the regenerated `deps.json`, the four new or changed guards, and the merged
  tree's gate; the rebase, the superseded refactor and the CI-only integration run are logged in the
  cycle log. Squashed for the PR. Status: done.

## Cycle log

- **Cycle 1 (test-list item 3, sqlite-jdbc)**, confirmed by the user 2026-11-06.
  - Test: `SqliteVersionSpec` now pins `3.54.1.0` (from the item's scope, independent of the resolved
    jar) and adds "exactly one sqlite-jdbc copy carries `org/sqlite/JDBC.class` on the classpath"
    (ADR 0042: a second jar would make the pinned version classpath-order dependent). Reads and writes
    are the repository specs, which this cycle runs on the new driver.
  - Red at `499fbe89` with the new test in the working tree (`gradle test --tests
    stt.db.SqliteVersionSpec`): `expected:<3.54.1.0> but was:<3.50.3.0>`; the single-copy test passed
    (2 tests, 1 failed).
  - Green: `sqlite-jdbc = "3.54.1.0"` in `backend/gradle/libs.versions.toml` only; the ADR 0042 force
    in `build.gradle.kts` is untouched. `dependencyInsight --dependency sqlite-jdbc`:
    `org.xerial:sqlite-jdbc:3.54.1.0 (forced)`, with flyway's `3.50.3.0 -> 3.54.1.0`.
  - `gradle check` at `499fbe89` with the catalog change and the test in the working tree: green,
    ktlintCheck + 427 tests, 0 failures, 1 skipped.
  - **Rebased onto `main` `06c304f1` (user's request, 2026-11-06), no conflicts.** The run commits
    above keep the hashes they ran at; the rebase moved them to `9ae686f0` (the log commit) and
    `7bc30fe5` (the cycle) with identical content: `git diff 0f670d56 499fbe89` and
    `git diff 06c304f1 9ae686f0` hash the same patch, as do `0f670d56..edca6e51` and
    `06c304f1..7bc30fe5` (sha256 of the patch: 226348eb… and 4dc8b631…). `06c304f1` adds only
    `roadmap/94-atomic-transitions-probe-clock.md` and its README row — no backend or build change,
    so cycle 1's green still describes the rebased backend tree.

- **Cycle 2 (test-list item 4, ONNX Runtime)**, confirmed by the user 2026-11-06.
  - Baseline before the bump, at `1a4d6f95` on 1.31.0: `gradle integrationTest` green — 469 tests,
    374 skipped by the tag filter, 95 executed, 0 failures (real whisper + CAM++ models;
    `EmbeddingsSpec` 7, `LibriSpeechDiarizationSpec` 1, `SpeakerThresholdSpec` 1, `SpeakerModelSpec` 2,
    `StreamedDiarizationIntegrationSpec` 2, `ProcessingEngineIntegrationSpec` 1 among them).
  - Test: `OnnxRuntimeSpec` gains "the native library is the catalog pin, 1.31.1" via
    `OrtEnvironment.getEnvironment().version` (an instance getter — the first draft called it
    statically and failed to compile; that compile error is not the red). It is the version of the
    bundled native, which is what the item-30 pins govern, not the jar metadata.
  - Red at `1a4d6f95` with the new test in the working tree (`gradle test --tests
    stt.diarization.OnnxRuntimeSpec`): `expected:<1.31.1> but was:<1.31.0>`; the native-load test
    passed (2 tests, 1 failed).
  - Green: `onnxruntime = "1.31.1"` in the catalog; the ADR 0042 force is untouched
    (`dependencyInsight`: `com.microsoft.onnxruntime:onnxruntime:1.31.1 (forced)`). The comments that
    named the pin (`build.gradle.kts`, `SpeakerModel`'s session-options KDoc) moved with it.
  - `gradle check` at `1a4d6f95` with the change in the working tree: green, ktlintCheck + 428 tests,
    0 failures, 1 skipped. `gradle integrationTest`: green, 95 executed, 0 failures — the embedding
    half of item 4 on 1.31.1. `OrtEnvironment.getEnvironment().version` reads the bundled native's
    version, so the pin is what the run proved.
  - The crash half of item 4 is the user's machine (item 30's crash never reproduced in the sandbox);
    left pending, item 4 not ticked yet.

- **Cycle 3 (test-list item 1, the remaining catalog)**, confirmed by the user 2026-11-06.
  - Change: Ktor 3.6.0 → 3.6.1, Kotest 6.2.5 → 6.2.6, Logback 1.6.5 → 1.6.6, Exposed 1.5.0 → 1.6.0,
    Flyway 13.9.0 → 13.12.0, JNA 5.14.0 → 5.19.1 — catalog only, no source change. Kotlin 2.3.21,
    kotlinx-serialization 1.11.0 (Ktor's, transitive), ktlint 1.8.0 and coroutines 1.11.0 unchanged;
    the catalog's Ktor-pair comment now names 3.6.1.
  - **No red obtainable, and none invented**: the new versions pass both suites with no edit, so
    Canon's red-before-green cannot be produced for a behaviour-neutral upgrade. User decision at the
    checkpoint: the suites and the comments are the record (the item-30 precedent, `c881c9c6`), and the
    bump is its own imperative commit rather than a `Test:` cycle.
  - Runs at `4294e360` with the catalog change in the working tree: `gradle check` green, ktlintCheck +
    428 tests, 0 failures, 1 skipped; `gradle integrationTest` green, 469 tests, 374 tag-skipped, 95
    executed, 0 failures.
  - The bump's only new warning: `PartData.dispose()` (`UploadRoutes.kt:113`) is deprecated in Ktor
    3.6.1 in favour of a suspend `release()` (probed in `ktor-http-jvm-3.6.1.jar`: `getDispose()`
    carries the annotation, `getRelease()` is the replacement). The `runCurrent()` opt-in warning in
    `ProcessingEngineEventsSpec.kt:37` predates the bump. Fixed as a separate approved refactor below
    rather than inside the green.

- **Approved refactor after cycle 3** (user, 2026-11-06): `UploadRoutes` releases each multipart part
  with Ktor 3.6.1's suspend `release()` instead of the now-deprecated `dispose()` — Ktor's 3.6.1 source
  documents `dispose` as "former blocking call to close the part; use release instead", so
  the change is behaviour-neutral and removes the bump's only new warning.
  - Run at `288b65de` with the refactor in the working tree: `gradle check` green (ktlintCheck +
    428 tests, 0 failures, 1 skipped, and the `dispose` deprecation warning gone);
    `gradle integrationTest` green (469 tests, 374 tag-skipped, 95 executed, 0 failures) — the
    multi-part upload suites (`UploadStoredInDatabaseSpec`, `UploadEngineWakeSpec`, the e2e service
    specs) exercise the released parts.

- **Cycle 4 (test-list item 2, migrations on fresh and existing databases)**, confirmed by the user
  2026-11-06.
  - Test: `DatabaseSpec` gains "every version keeps the checksum an existing stt.db recorded before the
    Flyway bump": after opening a fresh database, each `flyway_schema_history` row's stored checksum
    equals a frozen constant, in installed order. The expected values come from outside the current
    engine: V1-V12 as the pre-bump dev database (`.devenv/state/stt/stt.db`, written 2026-10-14,
    V1-V12 applied) records them, V13-V17 from the algorithm those rows prove — Flyway checksums a SQL
    migration as CRC32 over its lines without their terminators (checked: all 12 old rows reproduce
    exactly, e.g. V1 1474296966, V2 463302301, V11 -2094623219). V10 and V12 are Java migrations and
    store no checksum.
  - Red at `a48bae29` with the test in the working tree: `index 9: second: Expected null but actual
    was 0` for V10 and V12 — a bug in the new reader, not in the app: `getString("version")` ran
    before `wasNull()`, and a JDBC getter resets the flag. Probed the stored bytes instead of trusting
    either answer: a pristine copy of the pre-bump database and a fresh database created by 13.12.0
    both hold SQL `NULL` for V10/V12 (`typeof(checksum)` = null), so the constants stand and the
    reader was fixed to capture `wasNull()` right after `getInt`.
  - Teeth checked by mutation (the item-03 precedent): V17's constant changed to 384228313 at
    `a48bae29` → red `index 16: second: expected:<384228313> but was:<384228314>`; reverted.
  - **The existing-database half, on real data**: a copy of the pre-bump dev database (12 migrations
    applied, its V10/V12 rows SQL NULL) opened by this build through `Database.open` — it validated the
    recorded history and applied V13-V17 to 17 rows, success 1 throughout, no validation error. So
    Flyway 13.12.0's `validate` accepts a history written before the bump, as the release notes said;
    nothing baseline-related ran (the app calls only `migrate()` with defaults).
  - `gradle check` at `a48bae29` with the test in the working tree: green, ktlintCheck + 429 tests, 0
    failures, 1 skipped. `gradle integrationTest`: green, 469 tests, 374 tag-skipped, 95 executed, 0
    failures (the upgrade-from-V1 and upgrade-from-V2 scenarios, `AudioMigrationSpec`,
    `UpgradeCrashScratchIntegrationSpec` and the queue/history specs among them).

- **Cycle 5 (test-list item 5, Ktor's edge surface)**, confirmed by the user 2026-11-06: existing edge
  specs are the record, no new test.
  - Why no new test: the item's sentence is already asserted, and the one Ktor 3.6.1 behaviour change
    that first looked uncovered turned out to be covered. `VerifyCleanStop.kt` is a `testApplication`
    block followed by an assertion that the DI-resolved `Database` is closed, and `DatabaseDependencySpec`
    already asserts it — exactly KTOR-9430's "DI resources cleaned up during testing with
    testApplication" path. Ktor exposes no runtime version API (`ktor-utils` has no `KtorVersion` class),
    so the sqlite-jdbc/ONNX style pin has no analogue here.
  - The specs that are the record: upload errors — `UploadMultiFileSpec`, `UploadSizeLimitSpec`,
    `UploadValidationSpec` (400 codes and bodies); stream drop — `EventsDisconnectSpec` (a closed
    subscriber ends without an error logged while the other keeps receiving), with `EventsHeartbeatSpec`
    and `EventsIntegrationSpec`; DI — `DatabaseDependencySpec`; content negotiation — the `docs/api`
    contract specs (`ApiExamples`, `SettingsUpdateSpec`, `AllowedExtensionsContractSpec`,
    `LanguagesContractSpec`). All green in cycle 4's runs at `a48bae29` (`gradle check` 429 tests;
    `gradle integrationTest` 95 executed), so no new run is claimed for them.
  - Release-note triage for the bump, beyond the entries logged before cycle 1: KTOR-10023 (malformed
    paths) would surface as the app's own `internalErrorHandler` 500, which catches every `Throwable` —
    app-wide behaviour that predates the bump, unchanged by it, and out of this item's scope;
    KTOR-9790 (`sseSession()` with a heartbeat) and KTOR-9968 (test-engine streaming over 1 MB) are
    test-host fixes on paths `EventsDisconnectSpec`/`AudioLongPlaybackSpec` already exercise.

- **Cycle 6 (test-list item 6, Logback's pattern and the tag split)**, confirmed by the user
  2026-11-06.
  - Test: new `LogFormatSpec` renders lines through the **configured** layout — it takes the root
    logger's `STDOUT` appender, unwraps its `LayoutWrappingEncoder.getLayout()`, and lays out two
    `LoggingEvent`s. It asserts the root level is `INFO`, the appender is the console one, a line
    without `MDC.taskId` renders `HH:mm:ss.SSS INFO  <logger> - <msg>` with no task marker (the
    `%replace` that hides `[task ]`), and a line with `taskId=7` renders `… <logger> [task 7] - <msg>`.
    `LogCapture` only inspects events, so this is the first check of the rendered format.
  - Red: not obtainable by version (Logback 1.6.5 and 1.6.6 render this pattern identically). Teeth
    shown by mutation at `6d13ed61`: the expected no-task regex changed to three spaces after `INFO` →
    red `"11:35:02.433 INFO  stt.LogFormatProbe - hello\n" should match regex … INFO   stt\…`;
    reverted.
  - The tag split, from the same runs: the unit run reported 432 tests with 1 skipped (431 executed,
    none of them Integration-tagged) and the Integration run executed 95 (375 skipped) — Kotest 6.2.6
    still separates the suites exactly as before.
  - `gradle check` at `6d13ed61` with the new spec in the working tree: green, ktlintCheck + 432 tests,
    0 failures, 1 skipped. `gradle integrationTest`: green, 470 tests, 375 tag-skipped, 95 executed, 0
    failures.

- **Cycle 7 (test-list item 7, the Kotlin/serialization pair and ktlint)**, confirmed by the user
  2026-11-06.
  - Test: new `KotlinVersionSpec` pins `KotlinVersion.CURRENT` to `2.3.21`, the stdlib the app compiles
    and runs on — a transitive that raised it past Ktor's pair would red here. Red: none by version
    (unchanged), so teeth by mutation at `c5eb8f29`: expected `2.4.21` → `expected:<2.4.21> but
    was:<2.3.21>`; reverted.
  - Resolutions recorded (nothing in the test can see them):
    `dependencyInsight --dependency kotlin-stdlib --configuration runtimeClasspath` → 2.3.21;
    `--dependency kotlinx-serialization-json --configuration runtimeClasspath` → 1.11.0;
    `--dependency kotlinx-coroutines-core --configuration runtimeClasspath` → 1.11.0;
    `--dependency ktlint-cli --configuration ktlint` → 1.8.0. The catalog diff over this branch touches
    neither `kotlin` nor `ktlint` (serialization is not a catalog entry; it arrives with
    `ktor-serialization-kotlinx-json`).
  - `gradle check` at `c5eb8f29` with the new spec in the working tree: green, ktlintCheck + 433 tests,
    0 failures, 1 skipped. `gradle integrationTest`: green, 471 tests, 376 tag-skipped, 95 executed, 0
    failures.

- **Cycle 8 (test-list item 8, `deps.json` and the packaging gates)**, in progress 2026-11-07.
  - The stale lock was proven stale, and the gate proven to work: the user's `nix build .` at
    `a8fffbfc` (lock still 3.6.0/13.9.0/3.50.3.0/1.31.0) failed in the mitm proxy with "Could not find
    org.flywaydb:flyway-database-nc-sqlite:13.12.0", `org.xerial:sqlite-jdbc:3.54.1.0` and
    `com.microsoft.onnxruntime:onnxruntime:1.31.1` — exactly the newly pinned artifacts.
  - Regenerated by the user (Nix does not build in the agent sandbox), from the worktree root:
    `p=$(nix build .#backend.mitmCache.updateScript --print-out-paths) && bash "$p"` — the printed
    script ran to `BUILD SUCCESSFUL` / `gradleUpdateScript completed` and rewrote `backend/deps.json`
    (diff: 384 insertions, 385 deletions; the same 303 `pom` entries and 4 repository blocks).
  - The new lock's entries checked: `ktor-server-core-jvm/3.6.1`, `flyway-database-nc-sqlite/13.12.0`,
    `sqlite-jdbc/3.54.1.0`, `onnxruntime/1.31.1`, `exposed-core/1.6.0` present; none of the old
    `ktor-server-core-jvm/3.6.0`, `flyway-database-nc-sqlite/13.9.0`, `sqlite-jdbc/3.50.3.0`,
    `onnxruntime/1.31.0`, `exposed-core/1.5.0`, `logback-classic/1.6.5`,
    `kotest-runner-junit5-jvm/6.2.5` keys left. `jna/5.14.0` remains listed beside `jna/5.19.1`
    because a transitive (`mordant-jvm-jna`) requests it; `dependencyInsight` resolves
    `net.java.dev.jna:jna:5.19.1`.
  - `gradle checkVersions` (repository root, at `a8fffbfc`): green — "versions.json is in sync
    (Gradle 9.8.2, nixpkgs 0bb2eedc20e4, Rust 1.99.0, Windows pins present)".
  - Pending, both the user's: `nix build .` with the regenerated lock, and the e2e journeys (this
    sandbox has no Playwright browsers and no `e2e/node_modules`; the pull request's CI run is the
    other path).
  - `nix build .` with the regenerated lock (user, 2026-11-07): green, 4m39s, no output.
  - **The e2e journeys ran (agent sandbox, 2026-11-07, at `d4b8dd5d`)**: 57 passed, 7 skipped by
    platform/tag, 2 blocked by the container. The container has no `/bin/sh`, so every pnpm `.bin`
    shim and Gradle's `startScripts` launcher fails with "bad interpreter"; the journey therefore ran
    with the harness's own dev-mode override,
    `E2E_BACKEND_CMD="java -classpath backend/build/install/stt-backend/lib/* stt.MainKt"`, and its UI
    build (`tsc --noEmit`, `vite build`) was run through `node` and recorded with the suite's own
    `recordUiDigest` (`e2e: the built UI matches its sources`). The run's backend log shows the new
    stack booting: `Database: jdbc:sqlite:******** (SQLite 3.54)`, "Successfully validated 17
    migrations", "Schema is up to date", diarization summaries for every uploaded task.
    - Blocked, both the app-mode service specs that spawn
      `backend/build/install/stt-backend/bin/stt-backend` themselves instead of honouring
      `E2E_BACKEND_CMD`: `66-launch-token.spec.ts` (1 test) and `66-settings.spec.ts` (1 test). Nothing
      in either is touched by this item; they need a container with `/bin/sh` or CI.
    - Grep-free confirmation of the count: the first run reported 52 passed / 1 failed / 6 not run and
      stopped on `maxFailures: 1`; the 5 remaining runnable tests were then run by file
      (`72-frontend-errors`, `81-held-pause`, `87-writes-wait-for-the-connection`) and passed.

- **Item 4's crash half on the user's machine, 2026-11-07** (the only place the segfault ever
  reproduced; item 30 showed the sandbox runs any ORT release to completion): `gradle :backend:run` on
  the branch, two consecutive uploads from a fresh `/tmp/stt93` — `1089-134686-0000.flac` and
  `1089-134686-0002.flac` — both reached `COMPLETED` with `transcriptionProgress` and
  `diarizationProgress` 1.0, no exit 139, no core. Task 1's `/api/tasks/1/turns` returns one speaker
  (`speakerId` 0, "Speaker 1") with the LibriSpeech sentence transcribed ("he hoped there would be
  stew for dinner…"), so diarization ran on the real CAM++ model under ONNX Runtime 1.31.1. Item 4
  ticked.

- **Rebase onto `main` `775fad44` (#108–#112) at `44f4f964`** (2026-11-07, PR prep). Two
  conflicts, both consequences of `main` moving while this branch was in review:
  - `UploadRoutes.kt`: the approved `dispose()`→`release()` refactor is redundant — #108 landed the
    same change first. Resolved to `main`'s line, so this item's diff carries no `UploadRoutes`
    change; the refactor stays recorded here as approved and superseded.
  - `docs/known-issues.md`: #110 moved the e2e harness section above the footer; the new
    type-check row now sits in that table instead of at the file's end.
  Gates at the rebased head: `gradle check` green — ktlintCheck, 0 compiler warnings, 438 tests,
  0 failures, 1 skipped in 154 suites, 2m59s. The merge with `main` was also checked before the
  rebase with the same resolutions: `gradle check` green, 438 tests, 0 failures. `gradle
  integrationTest` on the merged tree is the pull request's CI run: #108 and #111 changed test-harness
  files after this branch's integration runs.

- **The pull request's CI run 42340965763, `Windows (gradle check)`**: `LogFormatSpec` pinned the
  formatted line's terminator to `\n`, but logback's `%n` is the platform separator (`\r\n` on
  Windows), so both pattern assertions now drop the terminator before matching
  (`trimEnd('\r', '\n')`) and assert the rendered pattern itself. The Linux sandbox cannot see this;
  the run's other two failures (Linux bundle e2e, `nix flake check`) are still being diagnosed.

- **The bundle e2e stall, diagnosed 2026-11-07 (CI run 42346225181 and a local reproduction)**:
  sqlite-jdbc 3.54.1.0 classifies the OS as `Linux-Musl` from the *host* — the bundle e2e runs in
  `alpine:3.20` — and extracts its musl native into the bundle's **glibc** JVM, where
  `libsqlitejdbc.so` cannot load (`libc.musl-x86_64.so.1: cannot open shared object file: No such
  file or directory`). `Database.open` then throws, the backend exits, and the window stays on
  `progress.html` until the wait expires. 3.50.3.0 did not classify the host that way, which is why
  `main` was green. Fix: `nix/desktop.nix`'s `tools` derivation now ships the jar's glibc
  `org/sqlite/native/Linux/x86_64/libsqlitejdbc.so` beside `whisper-cli` and `ffmpeg`, a directory
  `backend.ts` already puts on `LD_LIBRARY_PATH`/`java.library.path`, so the loader's
  `System.loadLibrary` fallback finds it; `unzip` fails the bundle build if a sqlite-jdbc bump moves
  the entry. Linux-only; verified by `nix eval` of the desktop and bundle derivations, with the
  e2e run as the confirmation.
  With `org.sqlite.lib.path`/`org.sqlite.lib.name` pointing at that file (backend.ts sets them
  only when the file exists), the loader takes it first and never classifies the host or extracts
  from the jar, so the failed-musl-load ERROR and its stack trace are gone too. Proved against the
  install dist with the extracted file: `/api/health` 200 in 4 s, no `sqlite-3.54.1.0-*` file in
  `/tmp`, no `Failed to load native library` line.
- 2026-11-07, the user's runs on the fixed bundle: `pnpm --dir e2e test:desktop:bundle` — 13 passed,
  4 skipped (the GPU and MSI specs), twice; the backend starts on `SQLite 3.54` with no
  `Failed to load native library` line. The temporary diagnostic that forwarded the app's output is
  removed before the PR; the Chromium GLX/EGL/dbus lines it surfaced are the headless container's.
