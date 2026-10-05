# Architecture decision records

One file per decision, `NNNN-<slug>.md`, format: title, `Status` + `Date` line, then `## Context`, `## Decision`, `## Consequences`.

New ADRs are appended with the next free number. A superseded ADR gets `Status: superseded by NNNN` and is never deleted.

| #    | Title                                                                                   | Status   | Date       |
| ---- | --------------------------------------------------------------------------------------- | -------- | ---------- |
| 0001 | [Stack and monorepo layout](0001-stack-and-monorepo.md)                                 | accepted | 2026-10-05 |
| 0002 | [No Python in the app; transcribe.py is a reference oracle only](0002-no-python-oracle-only.md) | accepted | 2026-10-05 |
| 0003 | [Diarization in Kotlin with CAM++ via ONNX Runtime](0003-kotlin-diarization-campplus-onnx.md) | accepted | 2026-10-05 |
| 0004 | [Chunked transcription with SIGSTOP/SIGCONT pause](0004-chunked-transcription-sigstop-pause.md) | accepted | 2026-10-05 |
| 0005 | [Uploaded audio stored as SQLite BLOB](0005-audio-as-sqlite-blob.md)                    | accepted | 2026-10-05 |
| 0006 | [Exposed, Flyway, and Kotest](0006-exposed-flyway-kotest.md)                            | accepted | 2026-10-05 |
| 0007 | [Server-Sent Events for live updates](0007-sse-live-updates.md)                          | accepted | 2026-10-05 |
| 0008 | [Default whisper model](0008-default-whisper-model.md)                                  | accepted | 2026-10-05 |
| 0009 | [Approval-gated Canon TDD workflow](0009-approval-gated-canon-tdd-workflow.md)          | accepted | 2026-10-05 |
| 0010 | [Development toolchain in devenv](0010-development-toolchain.md)                        | accepted | 2026-10-05 |
| 0011 | [One omp instance per roadmap workspace](0011-one-omp-instance-per-workspace.md)        | accepted | 2026-10-05 |
| 0012 | [Frontend dependency management: pnpm](0012-frontend-dependency-management-pnpm.md)      | accepted | 2026-10-05 |
| 0013 | [Backend structure: feature packages with Ktor DI](0013-backend-feature-packages-kotlin-di.md) | accepted | 2026-10-05 |
| 0014 | [Build tooling: Gradle catalog, no wrapper](0014-build-tooling-gradle-catalog-no-wrapper.md) | accepted | 2026-10-05 |
