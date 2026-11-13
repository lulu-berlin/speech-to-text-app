# 105 — The backend rewritten in Rust, phase 6: the engine, pause and the event stream

Status: spec-approved
Depends on: [101](101-rust-backend-process-control.md), [103](103-rust-backend-transcription.md), [104](104-rust-backend-diarization.md)
Blocks: [106](106-rust-backend-http-and-outputs.md)

## Goal

The worker that turns the queue into transcripts: claim the next `QUEUED` task, run chunking,
transcription and diarization in order, honor task and queue pause, resume, cancel and retry,
recover after a restart, clean every scratch path, publish the task and queue events the UI
consumes, and shut down cleanly.

## Evidence

50 suites, 120 scenarios: the 38 `stt/engine` suites (the worker loop, the start race, restart and
interrupted chunking, pause in every state, cancellation, retry, cleanup, scratch directories,
progress and phases, the detected language, the canonical storage, the whisper input, the
shutdown races) plus the 10 `stt/http` engine and events suites (`EngineWiring`, both shutdown
specs, the six `Events*` suites, `UploadEngineWakeSpec`, `UploadEventsSpec`). The guide §5 and §12
are the behavior; the scheduling seam decided in item 100 is what drives it.

## Proposal

1. **Worker**: one task loop over the queue, claiming through item 100's conditional transition,
   with the wake signal an upload sends; pause and stop flags checked at the same points as
   today.
2. **Stages**: chunking (item 102), transcription (item 103), diarization (item 104), and the
   cleanup that runs on success, failure and cancellation — including the "best effort, never
   masks an error" rule and the scratch-directory ownership per task.
3. **Recovery**: restarting the service turns `RUNNING` tasks back to `QUEUED` at their positions,
   removes unclaimed scratch and staged bytes, and resumes the first paused state.
4. **Events**: an event bus with the buffer, the cookie check, heartbeats, the overflow and
   disconnect behavior, and the queue/task JSON the stream carries (the same shapes the wire
   examples pin in item 106).

## Open questions

- **The seam in practice**: how the worker uses item 100's scheduler seam and whether the
  engine suites' virtual time covers the process waits or replaces them.
- **The event bus**: an in-process broadcast with per-subscriber buffers (what today is), or the
  buffer as a ring with a drop policy? The overflow suite pins the behavior.
- **Shutdown**: the graceful path's ordering (stop the worker, then the server) and the timeout
  the tests may wait for.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `engine/AwaitCompletedSpec.kt` — 1 scenario
- [ ] `engine/CountingMutexSpec.kt` — 7 scenarios
- [ ] `engine/EventBufferSpec.kt` — 1 scenario
- [ ] `engine/FilesUnderSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineAtomicTransitionsSpec.kt` — 6 scenarios
- [ ] `engine/ProcessingEngineCancelIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] `engine/ProcessingEngineCancelSpec.kt` — 3 scenarios
- [ ] `engine/ProcessingEngineCanonicalStorageSpec.kt` — 1 scenario (integration)
- [ ] `engine/ProcessingEngineChunkWindowsSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineChunkerScratchSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineChunkingScratchSpec.kt` — 4 scenarios
- [ ] `engine/ProcessingEngineChunkingSpec.kt` — 2 scenarios
- [ ] `engine/ProcessingEngineCleanupSpec.kt` — 3 scenarios
- [ ] `engine/ProcessingEngineCrashScratchSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineDetectedLanguageSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineDiarizationSpec.kt` — 6 scenarios
- [ ] `engine/ProcessingEngineEventsSpec.kt` — 2 scenarios
- [ ] `engine/ProcessingEngineFailureSpec.kt` — 12 scenarios
- [ ] `engine/ProcessingEngineIdleSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `engine/ProcessingEngineInterruptedChunkingSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineOriginalRemovalSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEnginePauseIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `engine/ProcessingEnginePhaseSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineProgressSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineQueuePauseSpec.kt` — 2 scenarios
- [ ] `engine/ProcessingEngineRestartIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `engine/ProcessingEngineRestartSpec.kt` — 6 scenarios
- [ ] `engine/ProcessingEngineRetrySpec.kt` — 3 scenarios
- [ ] `engine/ProcessingEngineSettingsSpec.kt` — 2 scenarios
- [ ] `engine/ProcessingEngineSpec.kt` — 11 scenarios
- [ ] `engine/ProcessingEngineStartRaceSpec.kt` — 3 scenarios
- [ ] `engine/ProcessingEngineStartupSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineTaskPauseSpec.kt` — 8 scenarios
- [ ] `engine/ProcessingEngineTranscriptionScratchSpec.kt` — 1 scenario
- [ ] `engine/ProcessingEngineWhisperInputIntegrationSpec.kt` — 3 scenarios (integration)
- [ ] `engine/ProcessingEngineWorkerLoopSpec.kt` — 2 scenarios
- [ ] `engine/StoppingFlagSpec.kt` — 1 scenario
- [ ] `http/EngineShutdownNormalStopSpec.kt` — 1 scenario
- [ ] `http/EngineShutdownRaceSpec.kt` — 1 scenario
- [ ] `http/EngineWiringSpec.kt` — 2 scenarios
- [ ] `http/EventsCookieSpec.kt` — 1 scenario
- [ ] `http/EventsDeletedTaskSpec.kt` — 1 scenario
- [ ] `http/EventsDisconnectSpec.kt` — 1 scenario
- [ ] `http/EventsHeartbeatSpec.kt` — 1 scenario
- [ ] `http/EventsIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `http/EventsOverflowSpec.kt` — 2 scenarios
- [ ] `http/EventsSpec.kt` — 1 scenario
- [ ] `http/UploadEngineWakeSpec.kt` — 1 scenario (integration)
- [ ] `http/UploadEventsSpec.kt` — 1 scenario
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes; the queue and live-update
journeys run against the Rust backend in item 108.

## Verification and CI

This phase's Rust code is verified locally and is not part of production: `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root (run by the
user), plus the parity suites above. The merge gate is the existing CI unchanged — Kotlin,
frontend, docs and the e2e journeys against the Kotlin backend; no Rust job exists until item
108 adds them.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list; `spec-approved`.
