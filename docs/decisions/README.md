# Architecture decision records

One file per decision, `NNNN-<slug>.md`, format: title, `Status` + `Date` line, then `## Context`, `## Decision`, `## Consequences`.

New ADRs are appended with the next free number. A superseded ADR gets `Status: superseded by NNNN` and is never deleted.

| #    | Title                                                                                   | Status   | Date       |
| ---- | --------------------------------------------------------------------------------------- | -------- | ---------- |
| 0001 | [Stack and monorepo layout](0001-stack-and-monorepo.md)                                 | accepted | 2026-10-05 |
| 0002 | [No Python in the app; transcribe.py is a reference oracle only](0002-no-python-oracle-only.md) | accepted | 2026-10-05 |
| 0003 | [Diarization in Kotlin with CAM++ via ONNX Runtime](0003-kotlin-diarization-campplus-onnx.md) | accepted, auto speaker-count rule amended by [0027](0027-auto-speaker-count-allows-one-speaker.md) | 2026-10-05 |
| 0004 | [Chunked transcription with SIGSTOP/SIGCONT pause](0004-chunked-transcription-sigstop-pause.md) | accepted | 2026-10-05 |
| 0005 | [Uploaded audio stored as SQLite BLOB](0005-audio-as-sqlite-blob.md)                    | superseded by [0024](0024-audio-storage-original-until-chunking-flac-chunks.md) | 2026-10-05 |
| 0006 | [Exposed, Flyway, and Kotest](0006-exposed-flyway-kotest.md)                            | accepted | 2026-10-05 |
| 0007 | [Server-Sent Events for live updates](0007-sse-live-updates.md)                          | accepted | 2026-10-05 |
| 0008 | [Default whisper model](0008-default-whisper-model.md)                                  | accepted | 2026-10-05 |
| 0009 | [Approval-gated Canon TDD workflow](0009-approval-gated-canon-tdd-workflow.md)          | accepted | 2026-10-05 |
| 0010 | [Development toolchain in devenv](0010-development-toolchain.md)                        | accepted | 2026-10-05 |
| 0011 | [One omp instance per roadmap workspace](0011-one-omp-instance-per-workspace.md)        | accepted | 2026-10-05 |
| 0012 | [Frontend dependency management: pnpm](0012-frontend-dependency-management-pnpm.md)      | accepted | 2026-10-05 |
| 0013 | [Backend structure: feature packages with Ktor DI](0013-backend-feature-packages-kotlin-di.md) | accepted | 2026-10-05 |
| 0014 | [Build tooling: Gradle catalog, no wrapper](0014-build-tooling-gradle-catalog-no-wrapper.md) | accepted, flake-scope part superseded by [0016](0016-flake-scope-correction-app-wrapper.md) | 2026-10-05 |
| 0015 | [Code formatting: ktlint and prettier](0015-code-formatting-ktlint-prettier.md)          | accepted | 2026-10-05 |
| 0016 | [Flake scope correction: app wrapper in item 02](0016-flake-scope-correction-app-wrapper.md) | accepted | 2026-10-05 |
| 0017 | [Database location, data directory, and connection contract](0017-database-location-and-connection-contract.md) | accepted | 2026-10-06 |
| 0018 | [Audio payload stored as paged BLOBs](0018-audio-payload-paged-blobs.md) | superseded by [0024](0024-audio-storage-original-until-chunking-flac-chunks.md) | 2026-10-06 |
| 0019 | [Schema ownership: one schema in item 03, migrations after](0019-schema-ownership-and-evolution.md) | accepted | 2026-10-06 |
| 0020 | [Process runner: JNA signals, process groups, cancellation](0020-process-runner-jna-signals-process-groups.md) | accepted | 2026-10-06 |
| 0021 | [Integration tests: Kotest tags and `gradle integrationTest`](0021-integration-tests-kotest-tags-gradle-task.md) | accepted | 2026-10-06 |
| 0022 | [Kotlin compiler runs inside the Gradle daemon](0022-kotlin-compiler-in-process.md)      | accepted | 2026-10-06 |
| 0023 | [Persistence may import feature-owned value types](0023-persistence-may-import-feature-value-types.md) | accepted | 2026-10-06 |
| 0024 | [Audio storage: original kept only until chunking, chunks stored as FLAC](0024-audio-storage-original-until-chunking-flac-chunks.md) | accepted | 2026-10-07 |
| 0025 | [Nix build uses Gradle's default JVM memory settings](0025-nix-build-gradle-default-jvm-memory.md) | accepted | 2026-10-08 |
| 0026 | [No rule for where test fixtures live](0026-no-rule-for-test-fixture-location.md)          | accepted | 2026-10-09 |
| 0027 | [Automatic speaker count allows one speaker](0027-auto-speaker-count-allows-one-speaker.md) | accepted | 2026-10-09 |
| 0029 | [Contract-first frontend with shared API examples](0029-contract-first-frontend-shared-api-examples.md) | accepted | 2026-10-09 |
| 0030 | [Segment text is trimmed in every output](0030-trimmed-segment-text-in-all-outputs.md) | accepted | 2026-10-09 |
