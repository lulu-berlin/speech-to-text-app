# 100 — The backend rewritten in Rust, phase 1: the crate, configuration, the database and the queue domain

Status: spec-approved
Depends on: [98](98-rust-workspace-at-root.md) (the root workspace, `crates/`, the toolchain file and both ADRs), [03](../docs/specs/database-schema.md), [04](../docs/specs/queue-domain-model.md), [18](../docs/specs/audio-in-database.md) (the schema the port opens and validates)
Blocks: 101–108 — the following phases, filed next to this item

## Goal

The first working slice of the Rust backend, and the project plan the rest of it follows: a
`crates/backend` crate that reads the same environment contract, opens and validates the current
`stt.db` (schema version 17), applies the same migrations, and implements the queue domain and the
repositories behind it, with **every scenario** of the phase-1 suites in
[`docs/backend-parity-tests.md`](../docs/backend-parity-tests.md) ported and green. The Kotlin
backend keeps serving production throughout; nothing switches in this item.

## Evidence

- **The behavior is specified, not guessed.** [`docs/backend-reimplementation.md`](../docs/backend-reimplementation.md)
  is the behavioral contract (sections 2, 3, 6 for this phase), and
  [`docs/backend-parity-tests.md`](../docs/backend-parity-tests.md) is the checklist: the counts
  below come from `gradle test` and `gradle integrationTest` on `222d3206`.
- **Phase 1's suites** (unit unless marked):
  - config: `stt/config/ConfigSpec.kt` — 10 scenarios.
  - database and migrations: `stt/db/DatabaseSpec.kt` — 14; `SqliteVersionSpec.kt` — 2;
    `TaskQueryPlanSpec.kt` — 1; `ConcurrencySpec.kt` — 3.
  - repositories: `stt/db/TaskRepositorySpec.kt` — 8; `SegmentRepositorySpec.kt` — 11;
    `ChunkRepositorySpec.kt` — 2; `QueueStateRepositorySpec.kt` — 2;
    `SettingsRepositorySpec.kt` — 1.
  - queue domain: `stt/queue/TaskStatusSpec.kt`, `TaskTransitionsContractSpec.kt` and
    `TaskQueueSpec.kt` — 20 together.
  - meta, ported as their Rust equivalents: `KotlinVersionSpec` (the compiler/runtime pin),
    `LogFormatSpec` (the configured log pattern), `MainSpec` (the entrypoint's arguments and
    failure on bad configuration), `TestTempDirSpec` (the private-temp-root harness).
- **What this phase does not need:** no HTTP, no SSE, no subprocess, no audio. A Rust binary that
  only opens the database and drives the queue can be exercised by its tests alone.

## Project plan (the rest of the rewrite)

One phase per item, each approved at its own consultation and merged as its own PR; the parity
document is the shared test list, grouped by area:

| Item | Phase | Area | Suites / scenarios |
| --- | --- | --- | --- |
| 100 | 1 | the crate and its harness, configuration, database and migrations, queue domain, and the scheduler-seam research | 21 / 84 |
| 101 | 2 | process control (spawn, stdout lines, stop, process trees; the whisper caller needs it first) | 8 / 41 |
| 102 | 3 | uploads and the audio pipeline (multipart, staged originals, decode, canonical clip, chunks) | 22 / 46 |
| 103 | 4 | transcription (whisper-cli invocation, prompts, parsing, progress) | 19 / 35 |
| 104 | 5 | diarization (fbank, CAM++ session, windows, clustering, speaker ids) | 15 / 53 |
| 105 | 6 | the engine, pause, restart recovery and the event stream | 50 / 120 |
| 106 | 7 | the remaining HTTP surface and the outputs (txt/srt/vtt) | 48 / 102 |
| 107 | 8 | app mode (service directories, durable port, private files, quit, tray supervision) | 15 / 22 |
| 108 | 9 | the switch: all suites and the 50 journeys against the Rust backend, packaging, remove the Kotlin backend | — |

Phases 2–9 are filed as items 101–108 next to this one, so each carries its area's test list
rather than one 199-suite list. Process control comes second because the whisper caller in
phases 4–6 is built on it; the audio pipeline is native, so it needs nothing spawned.

## Decided (consultation, 2026-11-10)

- One phase per item and PR, in the order above; the Kotlin backend serves production until phase
  9 switches it.
- Phase 1 is self-contained: no HTTP, no subprocess; its proof is its tests against a real SQLite
  file.
- `TaskQueueSpec` (16 scenarios) covers the queue aggregate that item 96 deletes as unused; the
  port keeps the behavior of `TaskStatus`, the transition contract and the repository operations,
  and does not recreate the aggregate. Recorded here so the parity count is honest.
- **Names**: the crate `crates/stt-service`, the binary `stt-service`; the product calls the
  backend the service.
- **One baseline, no replay, and existing databases are validated**: the Rust service creates the
  schema from the one consolidated baseline in
  [`docs/backend-schema.sql`](../docs/backend-schema.sql) — identical to the Kotlin migrations'
  end state — and carries only migrations written after it. It never replays the Kotlin
  migrations. An existing database is opened unchanged and validated before writing:
  `flyway_schema_history` exists and its highest installed version is 17, and every table and
  column is present with these names; an older (or otherwise unreadable) schema is a hard
  failure that names the file, not an upgrade path — there are no users and no stable release
  yet. `AudioMigrationSpec` and the "upgrades an existing installation" scenarios are dropped
  from parity. A new ADR records this, numbered next free when the item starts.
- **Native implementations over spawned apps**: wherever an external app can be replaced by a
  Rust implementation, it is — the audio pipeline decodes and encodes natively (item 102) and the
  whisper caller sits behind a trait so item 99's Rust engine replaces it without behavior
  change. Process control (item 101) exists for whatever remains spawned.
- **Driver**: `sqlx`, with its async pool (one write connection, a read connection per read,
  WAL, foreign keys, busy timeout). The SQLite it links is the newest version that can be
  installed reliably across all build paths (the Nix build and the cargo build), and
  `SqliteVersionSpec`'s port pins that version — not the 3.54.1.0 the Kotlin build happens to
  carry.
- **Scheduling**: this item waits for 67 and 98; no work starts under `client/`.
- **The upload boundary**: uploads do not transcribe, so the eight upload suites that need
  neither the engine nor the event stream belong to phase 3 (item 102). `UploadEngineWakeSpec`
  and `UploadEventsSpec` move to phase 6 (item 105), and `AudioMigrationSpec` is obsolete with a
  fresh start.

## Verification and CI (applies to every phase)

Each phase merges into `main` as usual, but until item 108 the Rust code is **not production
code and not gated by CI**:

- the phases' Rust gates are run by the user locally — `cargo fmt --check`,
  `cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root, plus the
  parity suites of the phase being worked;
- the merge gate is the existing CI unchanged: the Kotlin suite, the frontend, the docs checks and
  the e2e journeys against the Kotlin backend;
- no Rust job is added until item 108, which adds them and deletes the Kotlin backend with its
  Gradle build and CI jobs in the same change.

## Proposal

1. **The crate**: `crates/stt-service` in the root workspace item 98 creates, one library plus a
   `stt-service` binary that phase 9's packaging and the e2e harness point at; the workspace's
   `rust-toolchain.toml` and `rust-version` pin apply.
2. **Configuration**: the same `STT_*` variables as `Config.kt`, read once at startup, with the
   same defaults and the same startup failures naming the variable; no config file for the server.
3. **Database**: create the schema from the baseline DDL, with WAL, foreign keys and
   `journal_size_limit` as today; open an existing version-17 database unchanged after validating
   it, and refuse anything older; one write connection and a read connection per read; the
   repositories keep the exact SQL semantics the Kotlin ones have (conditional transitions, queue
   positions, the streaming audio model). `sqlx`'s migration runner records future migrations in
   its own table.
4. **Queue domain**: the status enum, the transition table, and the repository operations the
   engine and the routes use (claim the next QUEUED task, pause/resume/cancel/retry/delete,
   reorder, pending ids in queue order).
5. **Test harness**: private temp roots per suite, the migration DDL and an existing-database
   fixture, and a `Test:`-per-scenario cycle in the cycle log — the suite is the item's checklist
   unit, each scenario inside it is a TDD cycle with its own commit. The same step researches and
   decides the test scheduler seam (the virtual-time equivalent of `runTest`: paused
   `tokio::time`, a clock trait, or another), and records the choice in the item's log and the
   cycle log so phases 3–6 build on it.
6. **Parity proof**: every phase-1 scenario ported and green, the Kotlin suite unchanged and
   green, and the suite names kept (`config.rs`, `database.rs`, `task_repository.rs`, …) so the
   mapping stays 1:1.

## Open questions

None outstanding. The SQLite version follows the newest reliably installable one, and the test
scheduler seam is researched inside this item (step 5 below), so the engine phases inherit a
decided seam instead of rewriting one.

## Test list

Draft, to settle at the consultation. One line per suite; each scenario inside a suite is a TDD
cycle (`Test:` commit, red then green with the run's commit in the log), and the line is ticked
when the last scenario is green. Counts from `222d3206`.

- [ ] (basic) `ConfigSpec` — 10 scenarios: every variable's default, and each invalid or missing
      required value failing startup with a message naming the variable.
- [ ] (basic) `DatabaseSpec` — 14 scenarios: the baseline creates the schema from nothing; an
      existing version-17 database (Kotlin- or Rust-built) is opened unchanged, validated and
      written to; an older schema is refused with the file's name and nothing altered; WAL,
      foreign keys and `journal_size_limit` are on; a corrupt directory fails startup.
- [ ] (basic) The baseline equals [`docs/backend-schema.sql`](../docs/backend-schema.sql): every
      table, column, index, trigger and default the document names, the validation accepts a
      database the Kotlin migrations produced, and one future migration applies on top of it.
- [ ] (basic) `SqliteVersionSpec` — 2: the SQLite version the file reports and the one the build
      expects.
- [ ] (basic) `TaskQueryPlanSpec` — 1: the queue read uses the indexed plan.
- [ ] (basic) `ConcurrencySpec` — 3: writes serialize behind the writer lock, reads see only the
      committed state, upload staging is concurrent-safe.
- [ ] (basic) `TaskRepositorySpec` — 8: create from an upload, claim the next task, conditional
      transitions, terminal writes, pending order, pagination/history reads.
- [ ] (basic) `SegmentRepositorySpec` — 11: segment storage, edits and revert text, speakers,
      reassignment (including the new-speaker path), turns.
- [ ] (basic) `ChunkRepositorySpec` — 2: the canonical clip header and rows stored and read.
- [ ] (basic) `QueueStateRepositorySpec` — 2: the pause flag read and written.
- [ ] (basic) `SettingsRepositorySpec` — 1: the single settings row and its defaults.
- [ ] (basic) `TaskStatusSpec` and `TaskTransitionsContractSpec` — the status set and the
      transition table, every transition in `docs/api/task-transitions.json`.
- [ ] (basic) `TaskQueueSpec`'s behavior — the queue operations without the deleted aggregate:
      enqueue positions, reorder, removal on a terminal status, `pendingOrderedIds` order.
- [ ] (basic) The Rust equivalents of `KotlinVersionSpec`, `LogFormatSpec`, `MainSpec` and
      `TestTempDirSpec` — the compiler/runtime pin, the log pattern, the entrypoint's argument and
      configuration failures, the private temp-root harness.
- [ ] (must not break) The Kotlin suite stays green and unchanged (the reference until phase 9),
      and the parity document's phase-1 counts stay true.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes (the Kotlin backend still
serves). Phase 9 is where the existing 50 journeys run against the Rust backend.

## Approval log

- 2026-11-10: filed as a draft by the root agent at the user's request: the rewrite is done by the
  user without agentic assistance, so it is split into phases, one item and PR each, against
  `docs/backend-parity-tests.md` as the shared test list.
- 2026-11-10: the user approved the item, its test list and the decisions above (sqlx, one
  baseline from `docs/backend-schema.sql`, version-17 validation, the native implementation
  preference, `stt-service`, the wait for 67/98); `spec-approved`.
