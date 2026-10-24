# Architecture decision records

One file per decision, `NNNN-<slug>.md`, format: title, `Status` + `Date` line, then `## Context`, `## Decision`, `## Consequences`.

New ADRs are appended with the next free number. A superseded ADR gets `Status: superseded by NNNN` and is never deleted.

| #    | Title                                                                                   | Status   | Date       |
| ---- | --------------------------------------------------------------------------------------- | -------- | ---------- |
| 0001 | [Stack and monorepo layout](0001-stack-and-monorepo.md)                                 | accepted | 2026-10-05 |
| 0002 | [No Python in the app; transcribe.py is a reference oracle only](0002-no-python-oracle-only.md) | accepted, json output dropped by [0035](0035-no-json-download.md) | 2026-10-05 |
| 0003 | [Diarization in Kotlin with CAM++ via ONNX Runtime](0003-kotlin-diarization-campplus-onnx.md) | accepted, auto speaker-count rule amended by [0027](0027-auto-speaker-count-allows-one-speaker.md) | 2026-10-05 |
| 0004 | [Chunked transcription with SIGSTOP/SIGCONT pause](0004-chunked-transcription-sigstop-pause.md) | accepted | 2026-10-05 |
| 0005 | [Uploaded audio stored as SQLite BLOB](0005-audio-as-sqlite-blob.md)                    | superseded by [0024](0024-audio-storage-original-until-chunking-flac-chunks.md) | 2026-10-05 |
| 0006 | [Exposed, Flyway, and Kotest](0006-exposed-flyway-kotest.md)                            | accepted | 2026-10-05 |
| 0007 | [Server-Sent Events for live updates](0007-sse-live-updates.md)                          | accepted | 2026-10-05 |
| 0008 | [Default whisper model](0008-default-whisper-model.md)                                  | accepted, source moved to this repository's mirror (2026-10-15, [0055](0055-desktop-window-has-no-network-path.md)) | 2026-10-05 |
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
| 0024 | [Audio storage: original kept only until chunking, chunks stored as FLAC](0024-audio-storage-original-until-chunking-flac-chunks.md) | superseded by [0052](0052-canonical-clip-in-database-chunks-as-windows.md) | 2026-10-07 |
| 0025 | [Nix build uses Gradle's default JVM memory settings](0025-nix-build-gradle-default-jvm-memory.md) | accepted | 2026-10-08 |
| 0026 | [No rule for where test fixtures live](0026-no-rule-for-test-fixture-location.md)          | accepted | 2026-10-09 |
| 0027 | [Automatic speaker count allows one speaker](0027-auto-speaker-count-allows-one-speaker.md) | accepted | 2026-10-09 |
| 0028 | [Speakers table; segments reference their speaker by `speaker_id`](0028-speakers-table-and-segment-speaker-id.md) | accepted | 2026-10-09 |
| 0029 | [Contract-first frontend with shared API examples](0029-contract-first-frontend-shared-api-examples.md) | accepted | 2026-10-09 |
| 0030 | [Segment text is trimmed in every output](0030-trimmed-segment-text-in-all-outputs.md) | accepted, json download point dropped by [0035](0035-no-json-download.md) | 2026-10-09 |
| 0031 | [Frontend routing with react-router, state with XState](0031-frontend-routing-react-router-state-xstate.md) | accepted | 2026-10-09 |
| 0032 | [Speaker-distance threshold 0.5448 for CAM++](0032-speaker-distance-threshold-for-campplus.md) | accepted | 2026-10-09 |
| 0033 | [Design system: tokens, Radix primitives, IBM Plex Sans](0033-design-system-tokens-radix-plex.md) | accepted | 2026-10-09 |
| 0034 | [Speaker labels in downloads are the stored speaker names](0034-speaker-labels-are-stored-names.md) | accepted | 2026-10-09 |
| 0035 | [No json transcript download](0035-no-json-download.md) | accepted | 2026-10-09 |
| 0036 | [One nixpkgs pin shared by devenv and the flake](0036-shared-nixpkgs-pin.md) | accepted | 2026-10-09 |
| 0037 | [Nearest-neighbor chain for speaker clustering](0037-nearest-neighbor-chain-for-speaker-clustering.md) | accepted | 2026-10-09 |
| 0038 | [UI state transitions and form-submission flows are machines; controlled input values may stay `useState`](0038-ui-state-transitions-are-machines.md) | accepted | 2026-10-10 |
| 0039 | [The processing engine is created when the application starts](0039-engine-created-at-startup.md) | accepted | 2026-10-10 |
| 0040 | [Stenographer animation timing lives on the elements (JSX `style`)](0040-stenographer-timing-in-jsx.md) | accepted | 2026-10-10 |

| 0041 | [SSE without the Ktor plugin](0041-sse-without-ktor-plugin.md) | accepted, amends 0007 | 2026-10-10 |

| 0042 | [Catalog versions are authoritative via `resolutionStrategy.force`](0042-catalog-versions-authoritative.md) | accepted, amends 0014 | 2026-10-10 |

| 0043 | [ONNX Runtime is pinned like sqlite-jdbc](0043-onnxruntime-pinned-like-sqlite-jdbc.md) | accepted, applies 0042 to onnxruntime | 2026-10-11 |

| 0044 | [SSE heartbeat event and client inactivity watchdog](0044-sse-heartbeat-and-client-watchdog.md) | accepted, extends item 12's event list | 2026-10-11 |

| 0045 | [The joined playback FLAC is re-encoded (losslessly)](0045-joined-playback-flac-reencoded.md) | superseded by [0052](0052-canonical-clip-in-database-chunks-as-windows.md) | 2026-10-11 |
| 0046 | [Every app feature has an end-to-end test](0046-every-feature-has-an-e2e-test.md) | accepted | 2026-10-11 |
| 0047 | [External tools are resolved from PATH; no tool-path variables](0047-external-tools-resolved-from-path.md) | accepted, withdraws item 16's `STT_WHISPER_CLI`/`STT_FFMPEG` | 2026-10-11 |
| 0048 | [The task's id in the logging context (MDC) and named engine coroutines](0048-task-id-in-logging-context-and-named-coroutines.md) | accepted | 2026-10-12 |
| 0049 | [Gradle 9.8 in every build path; the root wrapper is for humans without Nix](0049-gradle-9-everywhere-root-wrapper-for-humans.md) | accepted, scopes 0014's "no Gradle wrapper" | 2026-10-12 |
| 0050 | [Roadmap items merge via human pull requests on GitHub; the root agent never merges](0050-pr-merge-on-github.md) | accepted, amends AGENTS.md § Workflow step 8 and § Non-negotiable rules (human-only pushes to GitHub) | 2026-10-14 |
| 0051 | [Existing databases rebuild the canonical clip inside a Flyway migration](0051-canonical-clip-backfill-migration.md) | accepted; the upgrade rebuilds each chunked task's clip (V10), then drops the per-chunk audio (V11) | 2026-10-14 |
| 0052 | [Audio storage: one canonical clip per task in the database; chunks are windows into it](0052-canonical-clip-in-database-chunks-as-windows.md) | accepted, supersedes 0024 and 0045 | 2026-10-14 |
| 0053 | [The Linux desktop app ships as a `.deb`, a flake package and a `nix bundle`; the Nix store is a model cache](0053-linux-desktop-deb-flake-package-bundle.md) | accepted | 2026-10-15 |
| 0054 | [No hand-written JavaScript: we write TypeScript, JavaScript only comes out of it](0054-no-hand-written-javascript.md) | accepted | 2026-10-15 |
| 0055 | [The desktop window has no network path: its own file and the loopback backend, nothing else](0055-desktop-window-has-no-network-path.md) | accepted | 2026-10-15 |
| 0056 | [Which shell a script starts with: `env` only where Nix is absent, pinned store interpreters otherwise](0056-shebang-env-only-outside-nix.md) | accepted | 2026-10-15 |
| 0057 | [A RUNNING task whose process is gone goes back to QUEUED](0057-running-task-without-process-is-queued.md) | accepted | 2026-10-17 |
| 0058 | [A QUEUED task whose start write fails is FAILED](0058-queued-task-whose-start-fails-is-failed.md) | accepted | 2026-10-18 |
| 0059 | [The Linux desktop app keeps Electron: Tauri 2's 24–32% saving is a cross-date comparison, the comparable unpacked saving is inferred at ~36%, and ADR 0055 cannot be kept](0059-os-shell-stays-electron.md) | accepted | 2026-10-20 |
| 0060 | [Windows releases through the Microsoft Store (MSIX); the macOS DMG stays unsigned with a documented Gatekeeper bypass](0060-windows-store-and-unsigned-macos.md) | accepted | 2026-10-23 |
| 0061 | [A segment keeps whisper's original text, and Revert restores it](0061-segment-keeps-original-text.md) | accepted, adds `edited` to the turns contract and `POST …/segments/{id}/revert` | 2026-10-23 |
