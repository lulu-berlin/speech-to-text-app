# 103 — The backend rewritten in Rust, phase 4: transcription

Status: spec-approved
Depends on: [101](101-rust-backend-process-control.md), [102](102-rust-backend-uploads-and-audio.md)
Blocks: [104](104-rust-backend-diarization.md), [105](105-rust-backend-engine-and-events.md)

## Goal

Turning a chunk into timed segments with whisper-cli: the device probe and its caching, the
command line and flags, the prompt tail and the resume prompt, the progress lines, the segment
parser, the detected language, and the per-task progress the engine reports.

## Evidence

`stt/transcribe`: 19 suites, 35 scenarios. The behavioral contract is the reimplementation guide
§6–§8 (whisper invocation, its output, the progress and language parsing), and the suites pin the
exact arguments (`WhisperCliFlagsSpec`), the JFK fixture's segments (`JfkIntegrationSpec`), the
device lines for Vulkan/Metal and the failure paths. The Kotlin suite stays the reference.

## Proposal

1. **Invocation**: the same `whisper-cli` binary and arguments — model path, threads, device,
   `--output-json`/the format flags, the language and the prompt tail — spawned through item
   101's runner, with the same environment.
2. **Device probe**: the one-off startup probe that asks whisper-cli which GPUs it can use, parsed
   from the same lines, cached for the process, and the failure path when it names none.
3. **Parsing**: JSON/segment output into segment rows with the same text normalization and
   timings; the detected language; the progress mapping to the task's `progress`.
4. **Prompts**: the prompt tail from the previous chunk's text and the resume prompt, exactly as
   the Kotlin builder produces them, so transcripts do not drift at chunk boundaries.

## Open questions

- **whisper-cli stays the engine**: this phase ports the current caller; item 99's Rust-native
  whisper, if it proceeds, replaces only the invocation behind the same interface. Confirm the
  interface is a trait so that swap needs no behavior change here.
- **Output format**: parse whisper's JSON output or its plain segments? The Kotlin transcriber
  reads which one today, and the byte-for-byte comparison of the transcript endpoints depends on
  the same choice.
- **Device probe timing**: the Kotlin service probes once at startup, in parallel with loading
  the UI. The Rust service must not delay readiness for it; confirm the same startup ordering.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `transcribe/ChunkSegmentsSpec.kt` — 1 scenario
- [ ] `transcribe/ChunkTranscriberSpec.kt` — 4 scenarios (integration)
- [ ] `transcribe/ChunkTranscriptionSpec.kt` — 2 scenarios (integration)
- [ ] `transcribe/DeviceArgsSpec.kt` — 1 scenario
- [ ] `transcribe/DeviceProbeErrorSpec.kt` — 1 scenario
- [ ] `transcribe/DeviceProbeInputSpec.kt` — 1 scenario
- [ ] `transcribe/DeviceProbeLoadFailureSpec.kt` — 4 scenarios
- [ ] `transcribe/DeviceProbeMetalSpec.kt` — 2 scenarios
- [ ] `transcribe/DeviceProbeSpec.kt` — 3 scenarios (integration)
- [ ] `transcribe/JfkIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `transcribe/ParseProgressSpec.kt` — 1 scenario
- [ ] `transcribe/ParseSegmentsSpec.kt` — 4 scenarios
- [ ] `transcribe/ParseWhisperLanguageSpec.kt` — 1 scenario
- [ ] `transcribe/PromptTailSpec.kt` — 2 scenarios
- [ ] `transcribe/ResumePromptSpec.kt` — 1 scenario
- [ ] `transcribe/TaskProgressSpec.kt` — 2 scenarios
- [ ] `transcribe/VulkanDeviceLinesSpec.kt` — 1 scenario
- [ ] `transcribe/WhisperCliFlagsSpec.kt` — 2 scenarios (integration)
- [ ] `transcribe/WhisperCommandSpec.kt` — 1 scenario
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes; the transcription journeys
run against the Rust backend in item 108.

## Verification and CI

This phase's Rust code is verified locally and is not part of production: `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root (run by the
user), plus the parity suites above. The merge gate is the existing CI unchanged — Kotlin,
frontend, docs and the e2e journeys against the Kotlin backend; no Rust job exists until item
108 adds them.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list; `spec-approved`.
