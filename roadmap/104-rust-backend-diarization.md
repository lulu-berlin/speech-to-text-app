# 104 — The backend rewritten in Rust, phase 5: diarization

Status: spec-approved
Depends on: [102](102-rust-backend-uploads-and-audio.md), [103](103-rust-backend-transcription.md)
Blocks: [105](105-rust-backend-engine-and-events.md)

## Goal

Speaker diarization in Rust: the fbank features, the CAM++ ONNX session and its model metadata,
the windowing and L2 normalization, the clustering into speakers, and the speaker ids, turns and
thresholds the transcript and its UI show — at the same accuracy as the Kotlin path.

## Evidence

`stt/diarization`: 15 suites, 53 scenarios — the pure parts (`FbankSpec` 6, `ClusteringSpec` 13,
`SpeakerIdsSpec` 7, `EmbeddingWindowsSpec` 5, `L2NormalizeSpec`, `SpeakerTurnsSpec`,
`SpeakerModelMetadataSpec`) and the model-bound ones (`EmbeddingsSpec` 7, `SpeakerModelSpec`,
`SpeakerThresholdSpec`, `StreamedDiarizationIntegrationSpec`, `LibriSpeechDiarizationSpec`,
`SpeakerDiarizerReloadIntegrationSpec`, `ClusteringHeapIntegrationSpec`). The guide §9–§10 is the
behavior; `docs/api/speakers-reassignment.md` and the speaker routes' contract (item 106) consume
what this item produces.

## Proposal

1. **Features**: fbank computed in Rust with the same coefficients, window and normalization as
   the Kotlin implementation, proven against `FbankSpec` and the stored embeddings fixture.
2. **Model**: the CAM++ ONNX model loaded once (reloadable) through an ONNX runtime, batched
   windows, L2-normalized embeddings.
3. **Clustering**: the same algorithm and similarity threshold; `SpeakerIdsSpec`'s values are
   the expected output, not a new tuning.
4. **Assembly**: windows aligned to the chunk's segments, turns and speaker ids written to the
   same tables, the reassignment path's inputs unchanged.

## Open questions

- **ONNX runtime — settled as a spike**: the item starts by trying `ort` (the ONNX Runtime native
  library) against the model and the embedding suite, and falls back to `tract` (pure Rust) if it
  does not work well; the outcome and the reason are recorded in the item's log. The packaging
  consequence (a native library in the Nix build and the desktop bundles) follows the winner.
- **Numerical parity**: whether embeddings may differ within a tolerance and still cluster the
  same (the fixtures compare speaker ids, not floats), and what tolerance the clustering suites
  allow.
- **Model loading**: memory and startup cost when the model loads on the first diarization as
  today; the probe's thread count applies to the session.
- **Windows**: the ONNX runtime must ship for Windows x64 too; item 108's packaging depends on
  this item's choice.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `diarization/ClusteringHeapIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] `diarization/ClusteringSpec.kt` — 13 scenarios
- [ ] `diarization/EmbeddingWindowsSpec.kt` — 5 scenarios
- [ ] `diarization/EmbeddingsSpec.kt` — 7 scenarios (integration)
- [ ] `diarization/FbankSpec.kt` — 6 scenarios
- [ ] `diarization/L2NormalizeSpec.kt` — 1 scenario
- [ ] `diarization/LibriSpeechDiarizationSpec.kt` — 1 scenario (integration)
- [ ] `diarization/OnnxRuntimeSpec.kt` — 2 scenarios
- [ ] `diarization/SpeakerDiarizerReloadIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `diarization/SpeakerIdsSpec.kt` — 7 scenarios
- [ ] `diarization/SpeakerModelMetadataSpec.kt` — 2 scenarios
- [ ] `diarization/SpeakerModelSpec.kt` — 2 scenarios (integration)
- [ ] `diarization/SpeakerThresholdSpec.kt` — 1 scenario (integration)
- [ ] `diarization/SpeakerTurnsSpec.kt` — 1 scenario
- [ ] `diarization/StreamedDiarizationIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes; the speaker journeys run
against the Rust backend in item 108.

## Verification and CI

This phase's Rust code is verified locally and is not part of production: `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root (run by the
user), plus the parity suites above. The merge gate is the existing CI unchanged — Kotlin,
frontend, docs and the e2e journeys against the Kotlin backend; no Rust job exists until item
108 adds them.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list, with the ONNX runtime settled as an
  in-item spike (`ort` first, `tract` as the fallback); `spec-approved`.
