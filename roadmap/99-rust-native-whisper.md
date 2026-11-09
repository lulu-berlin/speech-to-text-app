# 99 — A Rust-native whisper that matches whisper.cpp in speed and accuracy, with no bindings and no spawned process

Status: spec-approved
Depends on: [07](../docs/specs/whisper-transcription.md) (done; the transcription contract and the model the app ships), [05](../docs/specs/process-runner.md) (done; how whisper.cpp runs today, ADR 0047), [32](../docs/specs/settings-page.md) (done; the device and thread settings this would keep), [84](../docs/specs/e2e-reliability.md) (done; the fixtures and their reference transcripts), [98](98-rust-workspace-at-root.md) (the workspace layout the harness lands in)
Blocks: —

## Goal

A measurement, not a migration: decide on evidence whether a **pure-Rust** whisper engine — no
C/C++ bindings, no spawned process — can replace `whisper.cpp` inside the app at **at least the
same speed** and the same accuracy on **the model the app ships today**, on:

- CPU: the CI-class runner and the user's machines;
- NVIDIA and AMD GPUs on Linux and Windows, where **Vulkan** is the requirement;
- Apple Metal, measured on the GitHub Actions macOS runners.

ROCm is best-effort only: it cannot be measured here (the user has no ROCm box), so it is reported
as unverified and is never a gate. The deliverable is a benchmark harness, a report with the
numbers, and an **ADR that decides whether a migration is planned at all and, if so, to which
crate(s)**; the migration itself, if it is planned, is a separate item.

## Evidence

- **What the app does today.** `whisper.cpp` runs as a subprocess per task through the process
  runner (item 05, ADR 0047); the model is the pinned `ggml-large-v3-turbo-q5_0` (ADR 0008); device
  and thread count are settings (item 32) and are passed as CLI flags. The GPU probe is one
  `whisper-cli` call at startup.
- **The constraint from the user.** No C/C++ bindings and no external process. Excluded by that:
  `whisper-rs`, `sherpa-rs`, `ort`/ONNX Runtime bindings, `transcribe.cpp`, `parakeet.cpp`, MLX.
  The engine must run in-process from Rust.
- **Candidates to evaluate** (the survey is part of the item; none of these is endorsed yet):
  - **`wavo`** ([vladkens/wavo](https://github.com/vladkens/wavo)) — speech-to-text in pure Rust as
    a library and a CLI, Whisper turbo among its models, GGUF weights (Q8_0), Vulkan on
    Linux/Windows and GPU on macOS (Metal). Its published Parakeet/GigaAM benchmarks put it in the
    same band as `transcribe.cpp` on an M5 (0.14 s vs 0.12 s for a 5 s phrase; 160× vs 107× on long
    audio) and it reports 124× for Parakeet on an RTX 4060. It is young (0.4.x, ~26 stars), so
    maturity is part of what the item measures.
  - **`whisper-apr`** (crate `whisper_apr`, paiml/whisper.apr) — WASM-first pure-Rust Whisper:
    safetensors weights, a SIMD CPU path (`trueno`), a **WebGPU** compute backend (`wgpu` 23, so
    Vulkan/Metal/DX12), a backend-selection layer and its own multi-backend benchmark module, token
    timestamps, and a speaker-diarization module. Young like `wavo` (0.3.x, ~31 stars).
  - **`candle`** (Hugging Face) with its whisper example, and **`whisper-candle-core`**, a pure-Rust
    whisper port on candle — backends CPU, CUDA and Metal; no Vulkan.
  - **`burn`** (Tracel) with a whisper example — `wgpu` (Vulkan, Metal, DX12) and a CUDA backend;
    the whisper example's currency needs checking.
  - **`oxiwhisper`** — pure-Rust whisper with minimal dependencies; backend coverage to verify.
  - **`tract`** — pure-Rust ONNX on CPU only, if an ONNX whisper export is the cleaner route.
- **The hard parts, which the report must confront rather than average away:**
  - **Quantization.** The app ships a `q5_0` ggml model; most Rust stacks run f16/f32 or `q8_0`, so
    "same model" means the same *weights*, not the same bytes. The protocol compares f16 (or q8)
    everywhere, and reports the shipped `q5_0` baseline separately.
  - **Accuracy is more than the weights**: mel front-end, padding, chunking of long audio, and the
    tokenizer differ between implementations; the reference transcripts (item 84's fixtures) are the
    check.
  - **Backend coverage vs. the app's hardware**: CUDA (NVIDIA), Vulkan or ROCm (AMD), Metal
    (macOS). No candidate covers all three in one code path; the report says which does what.
  - **Kernels.** `whisper.cpp`'s ggml has hand-tuned SIMD/GPU kernels; a framework's generic matmul
    may not match it on CPU or on small batches.
- **The direction.** ADR 0068 (item 98) moves the app to Rust crate by crate; transcription is the
  largest remaining native dependency, so this item decides its replacement on numbers.

## Decided (consultation, 2026-11-09)

- **Hardware**: the user's machines plus the GitHub Actions macOS runners (Apple Silicon, Metal).
  The sandbox and the Linux/Windows CI runners have no GPU, so only CPU numbers come from CI.
- **Vulkan is required** wherever a Linux/Windows GPU is measured; Metal on macOS. **ROCm is
  nice-to-have**: it cannot be measured, so the report marks a candidate's ROCm path as unverified
  and the gates do not depend on it.
- **The model stays the one the app ships**: `ggml-large-v3-turbo-q5_0` (ADR 0008). An engine that
  cannot load that file gets the same weights converted to its format; the conversion lives in the
  harness, and every number states the format and quantization it ran, because a converted model is
  not the shipped one.
- **Thresholds**: p50 wall time within 10 % of `whisper.cpp` on each measured backend, and WER not
  worse on the fixture set.
- **One off**: one benchmark run per platform, on the fixtures the item names — not a repeated
  matrix across models, formats and repetition counts.
- **Deliverable**: the harness, the report, and an ADR that decides whether a migration is planned
  and to which crate(s). Integration is a separate item.

## Proposal

1. **A benchmark crate**, `crates/whisper-bench`, which is why item 98 comes first: the workspace
   moves to the root and the crate starts there rather than in a directory that would move again.
   One subcommand per engine and backend, one shared fixture loader (item 84's clips and reference
   transcripts), and a report writer (JSON plus Markdown).
2. **Protocol**, fixed once so runs compare:
   - Model: `ggml-large-v3-turbo-q5_0`, the file the app ships. An engine that cannot load ggml
     gets the same weights converted to its format, with the conversion script in the crate; the
     report labels every number with its format and quantization and never treats a converted model
     as the shipped one.
   - Settings mirrored from item 32: threads, device, no GPU when CPU is measured.
   - Runs: one measured pass per engine, backend and fixture set, with the harness's own warm-up;
     the deliverable is **one run per platform**, and the report states the host, the pass and the
     fixtures it used.
   - Metrics: wall time and real-time factor per fixture, p50 where the harness repeats within the
     pass, peak RSS/VRAM, and the transcript for scoring.
   - Accuracy: WER/CER against the reference transcripts, plus a per-engine diff of the outputs.
3. **Baseline**: the repo's pinned `whisper.cpp`, same host, same audio, the shipped `q5_0` model —
   the app's own configuration. Where a candidate runs a converted model, the report says so beside
   the number.
4. **Acceptance gates**: p50 wall time within 10 % of `whisper.cpp` on each **measured** backend,
   and WER not worse on the fixture set. Measured means CPU on the CI runner and the user's
   machines, **Vulkan** on the user's NVIDIA and AMD GPUs (Linux/Windows), and **Metal** on the
   GitHub macOS runners. ROCm is reported as unverified and is never a gate. A crate that wins on
   some backends only is reported as such; an unsupported backend is reported as unsupported, never
   skipped.
5. **Deliverable**: the harness, a `docs/` report (numbers, hosts, formats, how to reproduce), and
   an **ADR** that decides whether a migration is planned and, if so, to which crate(s) and
   backends — or that the app stays on `whisper.cpp`, with the measured reasons.

## Open questions

None. (Settled 2026-11-09, see "Decided"; the fixture set is the item's choice — item 84's `jfk`
clip and two LibriSpeech clips, single-shot inference, the way the app feeds a chunk.)

## Test list

Draft, to settle with the user. A research item: the harness has unit tests, and the parity gates
are measurements with a written report.

- [ ] (basic) The harness runs every candidate and the baseline on the same fixtures from one
      command and writes one report (JSON and Markdown) naming the host, the engine, the
      format/quantization and the fixture set.
- [ ] (basic) The baseline reproduces itself within the harness's stated noise floor on the same
      host, so a later candidate's difference means something.
- [ ] (basic) Each candidate's transcript is compared with the reference on the fixtures, and the
      report states the WER/CER gap (or exact equality) per engine and format.
- [ ] (basic) CPU parity: each candidate's p50 wall time against `whisper.cpp` on the shipped model
      is within the agreed 10 % on the CI runner and on the user's machines.
- [ ] (basic) Vulkan parity: the same measurement on the user's NVIDIA and AMD GPUs on Linux or
      Windows, with peak memory; Metal parity on the GitHub macOS runners.
- [ ] (edge) ROCm is reported as unverified for a candidate that offers it, and never turns a gate
      green or red.
- [ ] (edge) An engine that does not build, load its model, or run on a backend is reported as
      unsupported on that backend, with the error, never skipped silently.
- [ ] (edge) A candidate whose tokenizer, mel front-end or chunking differs yields a transcript
      quantified against the reference rather than accepted as equal.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a research item with no app behaviour change. The
harness uses item 84's fixtures, and the existing suites run unchanged as its must-not-break proof.

## Approval log

- 2026-11-09: filed as a draft by the root agent at the user's request: benchmark Rust whisper
  crates against `whisper.cpp` on speed and accuracy with the same model, on CPU and on NVIDIA, AMD
  and Metal, with no C/C++ bindings and no spawned process; the user named `wavo`, `candle` and
  `whisper_apr` as starting points.
- 2026-11-09: consultation (user): hardware is the user's machines plus the GitHub macOS runners;
  Vulkan required on Linux/Windows, Metal on macOS, ROCm nice-to-have and unmeasurable so never a
  gate; the model stays the one shipped, with same-weights conversions labelled; thresholds 10 %
  p50 and WER not worse; one off per platform. The deliverable is the ADR deciding whether a
  migration is planned and to which crates. Status stays `draft` until the test list is approved.
- 2026-11-09: approved: the proposal and the eight-line test list as written (the "app untouched"
  line was dropped — this item is pure research and never touches the app). Hardware, Vulkan/Metal
  and the ROCm caveat, the shipped model with labelled conversions, the 10 % p50 and WER gates, the
  one-off runs and the ADR deliverable are recorded under "Decided". Status set to spec-approved.
