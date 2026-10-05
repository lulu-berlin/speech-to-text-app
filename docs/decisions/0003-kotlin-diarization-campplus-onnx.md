# 0003 — Diarization in Kotlin with CAM++ via ONNX Runtime

Status: accepted · Date: 2026-10-05

## Context
ADR 0002 excludes Python, so the oracle's speechbrain ECAPA-TDNN embedder (PyTorch) cannot be used. The JVM needs a speaker-embedding model it can run natively, plus the audio feature front-end that model expects. The oracle's clustering, rescue, and turn-merging logic is plain numeric code that can be ported directly.

## Decision
- Diarization is re-implemented in Kotlin.
- Speaker embeddings: **3D-Speaker CAM++ (English, VoxCeleb)** ONNX model, run through **ONNX Runtime Java**.
  - Asset: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`
  - sha256: `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`
  - License: Apache-2.0
  - Output: 192-dimensional embedding.
- Front-end: an 80-dim Kaldi fbank implemented in Kotlin, using the sherpa-onnx `features.h` defaults this model was exported for:
  - 16 kHz sample rate, 80 mel bins
  - 25 ms frame length, 10 ms frame shift
  - povey window, dither 0, preemphasis 0.97, remove DC offset
  - low_freq 20, high_freq −400
  - snip_edges false, round_to_power_of_two true
  - then per-utterance mean subtraction (`feature_normalize_type = "global-mean"` in the model metadata)
  - input tensor shape `[1, T, 80]`
- At model load, read `output_dim`, `sample_rate`, `normalize_samples`, and `feature_normalize_type` from the ONNX metadata.
- Clustering (UPGMA + largest-gap speaker count), short-segment rescue, and turn merging are ported 1:1 from the oracle (`cluster_segments`, `_rescue_short_segments`, `speaker_turns` in [transcribe.py](../oracle/transcribe.py)).
- The oracle's n == 2 auto threshold (`heights_arr[0] < 0.35` cosine distance → one speaker, in `cluster_segments`) was tuned for ECAPA and is re-tuned for CAM++; the new value is recorded in a new ADR.

## Consequences
- No PyTorch/Python in the app; the model is a single ONNX file fetched by Nix with a pinned hash (development and packaging).
- The fbank front-end (including its FFT) must be written and unit-tested in Kotlin, since no dependency provides an equivalent Kaldi-compatible implementation.
- Embedding values differ from the oracle's, so tests compare clustering behavior on hand-built embeddings and same/different-speaker ordering on fixture clips, not oracle embedding numbers.
- ONNX Runtime ships a native library; on NixOS it may need `LD_LIBRARY_PATH` or `-Donnxruntime.native.path` set (handled in roadmap item 01).
- Threshold re-tuning needs fixture clips with known speakers.
