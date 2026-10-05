# 08 — Speaker embeddings

Status: draft
Depends on: [06](06-audio-decoding-and-chunking.md)

## Goal

Compute one L2-normalized speaker embedding per transcribed segment, in Kotlin, using the CAM++ ONNX model through ONNX Runtime Java and a Kotlin Kaldi-fbank front-end, with the oracle's windowing rules, so item 09 can cluster segments into speakers. The work is pausable between windows.

## Already decided

- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): model, sha256, front-end parameters, metadata keys, input shape `[1, T, 80]`, 512-d output.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): windowing ported from oracle `embed_windows`; segments < 0.5 s are excluded from clustering (item 09) but still embedded.
- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): in-JVM phases pause cooperatively via a pause gate between embedding windows.

## Proposal (pending approval)

- **Kaldi fbank in Kotlin** with the ADR 0003 parameters: 16 kHz, 80 mel bins, 25 ms frame (400 samples) / 10 ms shift (160 samples), povey window (`(0.5 − 0.5·cos(2πn/(N−1)))^0.85`), dither 0, remove DC offset per frame, pre-emphasis 0.97, low_freq 20 Hz, high_freq −400 (i.e. Nyquist − 400 = 7600 Hz), `snip_edges = false` (frame count `(samples + 80) / 160`, edges padded as Kaldi does), `round_to_power_of_two = true` (FFT size 512), power spectrum → mel filterbank → log with Kaldi's epsilon floor. Own FFT implementation (no equivalent in the dependencies). Exact formulas are taken from Kaldi / kaldi-native-fbank and cited in the code.
- **Normalization:** per-utterance mean subtraction over frames (`feature_normalize_type = "global-mean"`).
- **ONNX Runtime session** (`com.microsoft.onnxruntime:onnxruntime`, CPU) loaded once from `$STT_SPEAKER_MODEL` and reused for all tasks (the oracle's `load_embedder` likewise loads once and reuses). At load, read `output_dim`, `sample_rate`, `normalize_samples`, `feature_normalize_type` from the model metadata and fail fast if they don't match what the front-end supports (sample rate 16000, normalize type `global-mean`). Input/output tensor names are read from the session, not hard-coded. `normalize_samples` decides whether samples are fed as floats in [−1, 1] or scaled to the int16 range; the exact handling is confirmed against sherpa-onnx's speaker-embedding extractor.
- **Port oracle `embed_windows` windowing** (`docs/oracle/transcribe.py`), operating on the item 06 PCM (mono 16 kHz float32). Every segment is embedded, including segments < 0.5 s (oracle `diarize_and_write` embeds all of them because `_rescue_short_segments` needs their vectors). Per segment, with `sr = 16000`, `short = 2 s = 32000` samples, `cap = 20 s = 320000` samples, `total` = PCM length:
  - empty PCM (`total <= 0`) → every embedding is all zeros;
  - `t0 = start · sr`, `t1 = end · sr` (truncated to integers; from stored ms this is exactly `ms · 16`);
  - past-end / empty segment (`t1 <= t0` or `t0 >= total`) → tail window: `t0 = max(0, total − short)`, `t1 = total`;
  - `dur = min(t1, total) − t0`; if `dur <= 0` → an all-zero 2 s window;
  - `dur < short` → a 2 s zero buffer with the segment's audio placed centered at offset `(short − dur) / 2` (never borrowing neighbouring audio);
  - `dur > cap` → centered 20 s slice: `mid = (t0 + t1) / 2`, `st = max(t0, mid − cap / 2)`, `en = min(t1, st + cap)`, slice `[st, en)` clipped to `total`;
  - otherwise the slice `[t0, t0 + dur)`.
- **L2 normalization** of each embedding; a zero-norm vector stays all zeros (oracle sets the norm to 1).
- **One window per inference call** (`[1, T, 80]`): the oracle's length-sorted batching (`EMBED_CHUNK`, `EMBED_BATCH_SECONDS`) is a PyTorch memory workaround, not behavior, and is not ported. Results are returned in segment order.
- **Pause gate and cancellation** checked between windows; inference runs on `Dispatchers.Default` (CPU-bound). Progress = windows done / total windows.

## Open questions for the user

- Fixture clips for same/different-speaker tests: sherpa-onnx release WAVs (`fangjun-sr-1.wav` etc.) — license to confirm; committed to the repo or fetched by hash?
- Keep the oracle's 2 s zero-padding unchanged for CAM++ (zero samples hit the log floor of the fbank and shift the global mean), or re-evaluate it during the item 09 threshold tuning?
- Should the ONNX session use more than one intra-op thread (tie to `STT_THREADS`)?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Fbank of N samples yields `(N + 80) / 160` frames of 80 values (snip_edges false)
- [ ] The povey window of length 400 matches hand-computed values at its start, center and end
- [ ] Mel filterbank edges for 80 bins between 20 Hz and 7600 Hz at FFT size 512 match hand-computed bin positions
- [ ] Silence and a pure tone produce the expected relative fbank energies (tone bin dominates)
- [ ] Global-mean normalization makes every feature column average to zero
- [ ] The ONNX Runtime native library loads from the JVM inside the devenv shell (moved from item 01)
- [ ] Model metadata is read (`output_dim` 512, `sample_rate` 16000, `feature_normalize_type` global-mean); unsupported values are rejected
- [ ] A segment shorter than 2 s becomes a centered, zero-padded 2 s window
- [ ] A segment longer than 20 s becomes the centered 20 s slice
- [ ] A segment past the end of the audio uses the last 2 s (or the whole audio if shorter)
- [ ] Empty audio yields all-zero embeddings for every segment
- [ ] Embeddings have unit length; a zero vector stays zero
- [ ] Embeddings are returned in segment order
- [ ] Pausing stops embedding between windows and resuming continues with the next window
- [ ] Integration: same-speaker cosine similarity is higher than different-speaker cosine on the fixture clips

## Acceptance criteria

- Fbank unit tests pass against hand-computed values (frame count/shape, povey window, mel bank edges).
- Windowing tests cover every branch of oracle `embed_windows` listed above.
- Tagged `Integration` test with the real CAM++ model (`$STT_SPEAKER_MODEL`, sha256 from ADR 0003) shows same-speaker cosine > different-speaker cosine on the fixture clips.
- The ONNX session is created once per application run.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
