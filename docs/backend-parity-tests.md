# Backend parity: every test the Rust rewrite must carry over

Companion to [`docs/backend-reimplementation.md`](backend-reimplementation.md), which describes the
behavior. This document is the checklist: the current backend's tests, area by area, with the
scenario count each suite must reach on the Rust side. Baseline: `main` at `222d3206`
(2026-11-09); counts from `gradle test` (409 scenarios in 156 suites) and `gradle integrationTest`
on that commit.

## 1. The parity gate

A behavior is migrated when its listed scenarios exist and pass against the Rust backend — same
setup, same inputs, same assertions, expected values taken from the fixtures or the wire examples,
never from the new implementation. The Kotlin suite stays the reference and stays green until the
switch, so every ported scenario has two independent proofs.

The switch — packaging pointing at the Rust binary — happens only when all of:

- every unit suite in Annex A passes against the Rust backend, at its listed scenario count;
- every integration suite in §5 passes, with the real ffmpeg, whisper-cli and CAM++ model;
- the 41 wire examples are matched byte-for-byte where the Kotlin suite compares them, and the
  OpenAPI document stays the machine-readable contract;
- the 50 e2e journeys pass against the Rust backend serving the existing built UI;
- the backend-aware docs checks have a successor (see §3).

The switch itself and the packaging change are outside this document.

## 2. How to use it

- Port in the order of `backend-reimplementation.md` §14 (cycle groups A–I); each group lists the
  specs it covers, and the Kotlin suites below are the per-area detail of that list.
- Keep the suite names: the Rust module for `stt/http/UploadRoutesSpec.kt` is the port of that
  suite, so a tick here maps to a tick there.
- Port whole files, not the happy path: the counts are the target. A suite with 12 scenarios is
  done at 12, including its failure and edge scenarios (status codes, races, cancellation).
- Where a Kotlin suite exists only to pin a framework detail (for example `KotlinVersionSpec`,
  `LogFormatSpec`, `TestLoggingProbe`), the port pins its Rust equivalent (the MSRV, the log
  pattern, the logging shape) — the behavior it protects, not the library.

## 3. What is not a test but still gates parity

- **Wire examples** — `docs/api/*.json`, 41 files: `allowed-extensions`, `error`,
  `error.duplicate-audio`, `error.forbidden`, `error.invalid-device`, `error.invalid-language`,
  `error.invalid-reassignment`, `error.unauthorized`, `error.upload-too-large`, `frontends`,
  `heartbeat`, `languages`, `launch-token`, `queue`, `segment-edited`, `settings`,
  `settings.fallback`, `settings.fixed`, `settings.probe-failed`, `settings-updated`,
  `settings-updated.defaults`, `settings-updated.fallback`, `snapshot`, `speakers`,
  `speakers-merged`, `speakers-reassigned`, `speakers-renamed`, `task.canceled`, `task.completed`,
  `task.diarizing`, `task.failed`, `task.paused`, `task.queued`, `task.running`,
  `task-deleted`, `task-list`, `task-progress`, `task-progress.speakers-off`,
  `task-transitions`, `turns`, `turns.speakers-off`. The frontend's mock server (MSW 3, ADR 0066)
  serves these same bytes, so a difference shows up in the frontend's own tests too.
- **API contract** — `docs/api/openapi.yaml` and `docs/specs/http-api-and-sse.md`; the contract
  specs (`AllowedExtensionsContractSpec`, `LanguagesContractSpec`, `ApiExamples`, …) compare the
  examples with real responses.
- **e2e journeys** — 50 specs under `e2e/tests/`, run against the real backend: core (`01`–`18`),
  engine and upload hardening (`40-atomic-transitions`, `40-pause-queued`, `41-upload-hardening`,
  `42-worker-stability`), editing (`52-edit-journey`, `52-edit-segment`, `52-keyboard-trial`),
  connection and UI (`59-*`, `72-frontend-errors`, `81-held-pause`,
  `87-writes-wait-for-the-connection`), app mode (`60-device-probe`, `66-launch-token`,
  `66-settings`), plus the screenshot and gif recorders. Two harness facts matter for the port:
  the journeys can start the backend through `E2E_BACKEND_CMD`, and the app-mode specs spawn the
  installed launcher (`backend/build/install/stt-backend/bin/stt-backend`) unless the harness is
  given an equivalent for the Rust binary — plan that before flipping the switch.
- **Docs checks that read the backend** — `docs/check-exceptions.ts` (broad catches and
  `runCatching` in `backend/src/main/kotlin`), `docs/check-architecture-names.ts` and
  `check-architecture-diagram.ts` (code names and the diagram must exist in the source), and the
  docs package's source-comment guard (comments naming the retired tool). Each needs a Rust
  successor or an updated target; leaving them pointing at Kotlin would either fail or protect
  nothing.

## 4. Test infrastructure to re-create

- **Fixtures** — `backend/src/test/resources/librispeech/` (18 CC BY 4.0 clips + license),
  `upload-formats/` (19 per-extension fixtures + 4 variant encodings), `e2e` clips. Byte-for-byte;
  expected values depend on them.
- **Models** — `STT_WHISPER_MODEL` (`ggml-tiny.en` in tests) and `STT_SPEAKER_MODEL` (CAM++),
  from the Nix store; integration tests run against the real binaries.
- **Temp dirs** — `SharedTempDirs` / `TestTempDirSpec`: one private temp root per spec, cleaned
  by the suite, and the upload/scratch sweeps test against it.
- **Virtual time** — the engine and queue suites use `runTest` with `runCurrent`,
  `advanceTimeBy` and `TestScope.currentTime`; the Rust side needs a scheduler seam (a manual
  clock or a paused runtime) to keep those scenarios deterministic rather than sleeping.
- **Race machinery** — `TestEngine.kt`'s writer-lock probe (tickets, an append-only set of
  writers, a wait that is tied to the writer's own completion), the fake chunker (chunk count,
  hold/release, decode failures, unwritable plans), `TestQueue`, and the held-transaction helpers.
  The Rust tests need the same vocabulary or the atomic-transition specs cannot be ported
  faithfully.
- **Integration tag** — `gradle integrationTest` selects Kotest's `Integration` tag: 44 files
  carry it, 43 execute on Linux (Annex B); the Rust equivalent (`#[ignore]`, a feature, a separate
  binary) must keep both suites runnable separately, because CI runs them separately.
- **Process groups** — the process-control and pause suites need POSIX `setsid` and signal
  numbers (Linux) and the documented macOS/Windows differences (`backend-reimplementation.md`
  §17).

## 5. The integration suites

The 43 suites that `gradle integrationTest` executed on `222d3206` (98 scenarios) are listed in
Annex B. Every one needs the real whisper-cli, ffmpeg and CAM++ model; the process-control and
pause suites also need POSIX `setsid` (Linux) and the platform differences in
`backend-reimplementation.md` §17. `E2eTasksManagedNodeIntegrationSpec` carries the tag but is
skipped on Linux (it drives the Windows end-to-end managed-node scenario) — its scenarios need a
Windows host.

## Annex A — the unit suites (156 files, 409 scenarios)

Run `gradle test` on `222d3206`; every file below is one suite, with its scenario count. Port each
scenario, keep the name, and tick it in the port's own list.
#### stt (root package)

- `stt/KotlinVersionSpec.kt` — 1
- `stt/LogFormatSpec.kt` — 3
- `stt/MainSpec.kt` — 1
- `stt/TestLoggingProbe.kt` — 1
- `stt/TestTempDirSpec.kt` — 1
- `stt/WindowsStartScriptSpec.kt` — 1

#### stt/audio

- `stt/audio/CanonicalClipChannelSpec.kt` — 1
- `stt/audio/ChunkPlannerSpec.kt` — 8
- `stt/audio/FlacStreamInfoSpec.kt` — 1
- `stt/audio/ParseSilencesSpec.kt` — 1

#### stt/config

- `stt/config/ConfigSpec.kt` — 10

#### stt/db

- `stt/db/ChunkRepositorySpec.kt` — 2
- `stt/db/ConcurrencySpec.kt` — 3
- `stt/db/DatabaseSpec.kt` — 14
- `stt/db/QueueStateRepositorySpec.kt` — 2
- `stt/db/SegmentRepositorySpec.kt` — 11
- `stt/db/SettingsRepositorySpec.kt` — 1
- `stt/db/SqliteVersionSpec.kt` — 2
- `stt/db/TaskQueryPlanSpec.kt` — 1
- `stt/db/TaskRepositorySpec.kt` — 8

#### stt/diarization

- `stt/diarization/ClusteringSpec.kt` — 13
- `stt/diarization/EmbeddingWindowsSpec.kt` — 5
- `stt/diarization/FbankSpec.kt` — 6
- `stt/diarization/L2NormalizeSpec.kt` — 1
- `stt/diarization/OnnxRuntimeSpec.kt` — 2
- `stt/diarization/SpeakerIdsSpec.kt` — 7
- `stt/diarization/SpeakerModelMetadataSpec.kt` — 2
- `stt/diarization/SpeakerTurnsSpec.kt` — 1

#### stt/engine

- `stt/engine/AwaitCompletedSpec.kt` — 1
- `stt/engine/CountingMutexSpec.kt` — 7
- `stt/engine/EventBufferSpec.kt` — 1
- `stt/engine/FilesUnderSpec.kt` — 1
- `stt/engine/ProcessingEngineAtomicTransitionsSpec.kt` — 6
- `stt/engine/ProcessingEngineCancelSpec.kt` — 3
- `stt/engine/ProcessingEngineChunkWindowsSpec.kt` — 1
- `stt/engine/ProcessingEngineChunkerScratchSpec.kt` — 1
- `stt/engine/ProcessingEngineChunkingScratchSpec.kt` — 4
- `stt/engine/ProcessingEngineChunkingSpec.kt` — 2
- `stt/engine/ProcessingEngineCleanupSpec.kt` — 3
- `stt/engine/ProcessingEngineCrashScratchSpec.kt` — 1
- `stt/engine/ProcessingEngineDetectedLanguageSpec.kt` — 1
- `stt/engine/ProcessingEngineDiarizationSpec.kt` — 6
- `stt/engine/ProcessingEngineEventsSpec.kt` — 2
- `stt/engine/ProcessingEngineFailureSpec.kt` — 12
- `stt/engine/ProcessingEngineIdleSpec.kt` — 1
- `stt/engine/ProcessingEngineInterruptedChunkingSpec.kt` — 1
- `stt/engine/ProcessingEngineOriginalRemovalSpec.kt` — 1
- `stt/engine/ProcessingEnginePhaseSpec.kt` — 1
- `stt/engine/ProcessingEngineProgressSpec.kt` — 1
- `stt/engine/ProcessingEngineQueuePauseSpec.kt` — 2
- `stt/engine/ProcessingEngineRestartSpec.kt` — 6
- `stt/engine/ProcessingEngineRetrySpec.kt` — 3
- `stt/engine/ProcessingEngineSettingsSpec.kt` — 2
- `stt/engine/ProcessingEngineSpec.kt` — 11
- `stt/engine/ProcessingEngineStartRaceSpec.kt` — 3
- `stt/engine/ProcessingEngineStartupSpec.kt` — 1
- `stt/engine/ProcessingEngineTaskPauseSpec.kt` — 8
- `stt/engine/ProcessingEngineTranscriptionScratchSpec.kt` — 1
- `stt/engine/ProcessingEngineWorkerLoopSpec.kt` — 2
- `stt/engine/StoppingFlagSpec.kt` — 1

#### stt/http

- `stt/http/AllowedExtensionsContractSpec.kt` — 1
- `stt/http/AudioDownloadSpec.kt` — 2
- `stt/http/AudioRangeSpec.kt` — 2
- `stt/http/BackupDatabaseFileSpec.kt` — 1
- `stt/http/BrowserRefusalSpec.kt` — 1
- `stt/http/CredentialSpec.kt` — 1
- `stt/http/DatabaseDependencySpec.kt` — 2
- `stt/http/EngineShutdownNormalStopSpec.kt` — 1
- `stt/http/EngineShutdownRaceSpec.kt` — 1
- `stt/http/EngineWiringSpec.kt` — 2
- `stt/http/EventsCookieSpec.kt` — 1
- `stt/http/EventsDeletedTaskSpec.kt` — 1
- `stt/http/EventsDisconnectSpec.kt` — 1
- `stt/http/EventsHeartbeatSpec.kt` — 1
- `stt/http/EventsOverflowSpec.kt` — 2
- `stt/http/EventsSpec.kt` — 1
- `stt/http/FrontendsSpec.kt` — 2
- `stt/http/HealthSpec.kt` — 1
- `stt/http/InternalErrorSpec.kt` — 1
- `stt/http/LanguagesContractSpec.kt` — 1
- `stt/http/LaunchTokenSpec.kt` — 1
- `stt/http/QueuePauseResumeSpec.kt` — 1
- `stt/http/QueueReorderMismatchSpec.kt` — 2
- `stt/http/QueueReorderSpec.kt` — 1
- `stt/http/SegmentEditRoutesSpec.kt` — 7
- `stt/http/SegmentRevertSpec.kt` — 1
- `stt/http/SettingsDefaultFrontendSpec.kt` — 1
- `stt/http/SettingsFallbackSpec.kt` — 2
- `stt/http/SettingsResetSpec.kt` — 1
- `stt/http/SettingsRoutesSpec.kt` — 3
- `stt/http/SettingsTerminalCommandSpec.kt` — 1
- `stt/http/SettingsUpdateSpec.kt` — 2
- `stt/http/SpaFallbackSpec.kt` — 2
- `stt/http/SpeakerRoutesSpec.kt` — 25
- `stt/http/StaleDefaultFrontendSpec.kt` — 1
- `stt/http/StaticDisabledSpec.kt` — 1
- `stt/http/StaticFilesSpec.kt` — 1
- `stt/http/TaskCommandsIllegalSpec.kt` — 1
- `stt/http/TaskCommandsSpec.kt` — 5
- `stt/http/TaskDeletePendingSpec.kt` — 1
- `stt/http/TaskDeleteSpec.kt` — 1
- `stt/http/TaskListSpec.kt` — 1
- `stt/http/TaskRoutesSpec.kt` — 5
- `stt/http/TranscriptDownloadErrorsSpec.kt` — 1
- `stt/http/TranscriptDownloadSpec.kt` — 2
- `stt/http/TrayRoutesSpec.kt` — 1
- `stt/http/TrustedOriginSpec.kt` — 1
- `stt/http/TurnsSpec.kt` — 4
- `stt/http/UnknownApiPathSpec.kt` — 2
- `stt/http/UnknownTaskSpec.kt` — 1
- `stt/http/UploadCleanupSpec.kt` — 1
- `stt/http/UploadDuplicateRaceSpec.kt` — 1
- `stt/http/UploadDuplicateSpec.kt` — 1
- `stt/http/UploadEventsSpec.kt` — 1
- `stt/http/UploadLeavesNoBytesSpec.kt` — 1
- `stt/http/UploadMultiFileSpec.kt` — 1
- `stt/http/UploadSizeLimitSpec.kt` — 3
- `stt/http/UploadStoredInDatabaseSpec.kt` — 4
- `stt/http/UploadStreamingSpec.kt` — 3
- `stt/http/UploadValidationSpec.kt` — 2

#### stt/outputs

- `stt/outputs/DownloadFileNameSpec.kt` — 1
- `stt/outputs/NoSpeechSpec.kt` — 2
- `stt/outputs/RenderSrtSpec.kt` — 1
- `stt/outputs/RenderTxtSpec.kt` — 1
- `stt/outputs/RenderVttSpec.kt` — 1
- `stt/outputs/SpeakerLabelsSpec.kt` — 1
- `stt/outputs/SpeakersOffSpec.kt` — 2
- `stt/outputs/TimestampsSpec.kt` — 2

#### stt/process

- `stt/process/ProcessRunnerLibcSpec.kt` — 2
- `stt/process/ProcessRunnerPlatformSpec.kt` — 4
- `stt/process/ProcessRunnerStoppingSpec.kt` — 2
- `stt/process/ProcessRunnerWindowsSpec.kt` — 4
- `stt/process/ProcessTreeSignalsSpec.kt` — 2
- `stt/process/ProcessTreeSpec.kt` — 1
- `stt/process/ReadLinesSpec.kt` — 2

#### stt/queue

- `stt/queue/TaskQueueSpec.kt` — 16
- `stt/queue/TaskStatusSpec.kt` — 2
- `stt/queue/TaskTransitionsContractSpec.kt` — 2

#### stt/service

- `stt/service/AtomicWriteSpec.kt` — 1
- `stt/service/ServiceDirsSpec.kt` — 1
- `stt/service/ServiceServerSpec.kt` — 2
- `stt/service/TraySupervisorSpec.kt` — 4

#### stt/transcribe

- `stt/transcribe/ChunkSegmentsSpec.kt` — 1
- `stt/transcribe/DeviceArgsSpec.kt` — 1
- `stt/transcribe/DeviceProbeErrorSpec.kt` — 1
- `stt/transcribe/DeviceProbeInputSpec.kt` — 1
- `stt/transcribe/DeviceProbeLoadFailureSpec.kt` — 4
- `stt/transcribe/DeviceProbeMetalSpec.kt` — 2
- `stt/transcribe/ParseProgressSpec.kt` — 1
- `stt/transcribe/ParseSegmentsSpec.kt` — 4
- `stt/transcribe/ParseWhisperLanguageSpec.kt` — 1
- `stt/transcribe/PromptTailSpec.kt` — 2
- `stt/transcribe/ResumePromptSpec.kt` — 1
- `stt/transcribe/TaskProgressSpec.kt` — 2
- `stt/transcribe/VulkanDeviceLinesSpec.kt` — 1
- `stt/transcribe/WhisperCommandSpec.kt` — 1

## Annex B — the integration suites (43 files, 98 scenarios)

Run `gradle integrationTest` on `222d3206`; each line is one suite and its executed
scenario count. These need the real tools and models, and CI runs them separately from the unit
suite. Port them after the unit suite of their area, against the real binaries.

#### stt (root package)

- `stt/StopOnStdinCloseIntegrationSpec.kt` — 2
- `stt/TestLoggingIntegrationSpec.kt` — 1
- `stt/TestTempDirIntegrationSpec.kt` — 1

#### stt/audio

- `stt/audio/AudioChunkerSpec.kt` — 5
- `stt/audio/AudioDecoderSpec.kt` — 3
- `stt/audio/ChunkingHeapBoundIntegrationSpec.kt` — 1
- `stt/audio/FlacSplitterSpec.kt` — 2
- `stt/audio/UploadFormatsSpec.kt` — 1

#### stt/diarization

- `stt/diarization/ClusteringHeapIntegrationSpec.kt` — 2
- `stt/diarization/EmbeddingsSpec.kt` — 7
- `stt/diarization/LibriSpeechDiarizationSpec.kt` — 1
- `stt/diarization/SpeakerDiarizerReloadIntegrationSpec.kt` — 1
- `stt/diarization/SpeakerModelSpec.kt` — 2
- `stt/diarization/SpeakerThresholdSpec.kt` — 1
- `stt/diarization/StreamedDiarizationIntegrationSpec.kt` — 2

#### stt/engine

- `stt/engine/ProcessingEngineCancelIntegrationSpec.kt` — 2
- `stt/engine/ProcessingEngineCanonicalStorageSpec.kt` — 1
- `stt/engine/ProcessingEngineIntegrationSpec.kt` — 1
- `stt/engine/ProcessingEnginePauseIntegrationSpec.kt` — 1
- `stt/engine/ProcessingEngineRestartIntegrationSpec.kt` — 1
- `stt/engine/ProcessingEngineWhisperInputIntegrationSpec.kt` — 3

#### stt/http

- `stt/http/AudioCanonicalServingSpec.kt` — 1
- `stt/http/AudioLongPlaybackSpec.kt` — 1
- `stt/http/AudioMigrationSpec.kt` — 4
- `stt/http/EventsIntegrationSpec.kt` — 1
- `stt/http/UpgradeCrashScratchIntegrationSpec.kt` — 1
- `stt/http/UploadEngineWakeSpec.kt` — 1

#### stt/process

- `stt/process/ProcessRunnerSpec.kt` — 24

#### stt/service

- `stt/service/CleanStopIntegrationSpec.kt` — 1
- `stt/service/DefaultPortIntegrationSpec.kt` — 1
- `stt/service/DurableStateIntegrationSpec.kt` — 1
- `stt/service/LoopbackOnlyIntegrationSpec.kt` — 1
- `stt/service/PortFallbackIntegrationSpec.kt` — 2
- `stt/service/PrivateFilesIntegrationSpec.kt` — 1
- `stt/service/QuitIntegrationSpec.kt` — 1
- `stt/service/SecretHygieneIntegrationSpec.kt` — 1
- `stt/service/SingleServiceIntegrationSpec.kt` — 1
- `stt/service/UnreadableConfigIntegrationSpec.kt` — 2

#### stt/transcribe

- `stt/transcribe/ChunkTranscriberSpec.kt` — 4
- `stt/transcribe/ChunkTranscriptionSpec.kt` — 2
- `stt/transcribe/DeviceProbeSpec.kt` — 3
- `stt/transcribe/JfkIntegrationSpec.kt` — 1
- `stt/transcribe/WhisperCliFlagsSpec.kt` — 2
