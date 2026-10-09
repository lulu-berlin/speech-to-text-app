# 08 — Speaker embeddings

Status: in-progress
Depends on: [06](../docs/specs/audio-decoding-and-chunking.md) (done)

## Goal

Compute one L2-normalized speaker embedding per transcribed segment, in Kotlin, using the CAM++ ONNX model through ONNX Runtime Java and a Kotlin Kaldi-fbank front-end, with the oracle's windowing rules, so item 09 can cluster segments into speakers. The work is pausable between windows.

## Already decided

- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): model, sha256, front-end parameters, metadata keys, input shape `[1, T, 80]`, 512-d output.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): windowing ported from oracle `embed_windows`; segments < 0.5 s are excluded from clustering (item 09) but still embedded.
- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): in-JVM phases pause cooperatively via a pause gate between embedding windows.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole-file PCM is always rebuilt from the task's stored 16-bit FLAC chunks (item 11), with or without a restart.

## Proposal (approved 2026-10-07)

- **Kaldi fbank in Kotlin** with the ADR 0003 parameters: 16 kHz, 80 mel bins, 25 ms frame (400 samples) / 10 ms shift (160 samples), povey window (`(0.5 − 0.5·cos(2πn/(N−1)))^0.85`), dither 0, remove DC offset per frame, pre-emphasis 0.97, low_freq 20 Hz, high_freq −400 (i.e. Nyquist − 400 = 7600 Hz), `snip_edges = false` (frame count `(samples + 80) / 160`, edges padded as Kaldi does), `round_to_power_of_two = true` (FFT size 512), power spectrum → mel filterbank → log with Kaldi's epsilon floor. Own FFT implementation (no equivalent in the dependencies). Exact formulas are taken from Kaldi / kaldi-native-fbank and cited in the code.
- **Normalization:** per-utterance mean subtraction over frames (`feature_normalize_type = "global-mean"`).
- **ONNX Runtime session** (`com.microsoft.onnxruntime:onnxruntime`, CPU) loaded once from `$STT_SPEAKER_MODEL` and reused for all tasks (the oracle's `load_embedder` likewise loads once and reuses). At load, read `output_dim`, `sample_rate`, `normalize_samples`, `feature_normalize_type` from the model metadata and fail fast if they don't match what the front-end supports (sample rate 16000, normalize type `global-mean`). Input/output tensor names are read from the session, not hard-coded. `normalize_samples` decides whether samples are fed as floats in [−1, 1] or scaled to the int16 range; the exact handling is confirmed against sherpa-onnx's speaker-embedding extractor. Intra-op threads: `STT_THREADS` when it is set, otherwise ONNX Runtime's default, the same rule as whisper-cli's `-t` in item 07 (consultation 2026-10-07). The new dependency goes into the Gradle catalog; `backend/deps.json` is regenerated and `nix build .` re-verified by the user.
- **Port oracle `embed_windows` windowing** (`docs/oracle/transcribe.py`), operating on the task's whole-file PCM (mono 16 kHz float32), which item 11 rebuilds from the stored 16-bit FLAC chunks (ADR 0024): the sample values are 16-bit-quantized, unlike the oracle's float32 decode of the original. Every segment is embedded, including segments < 0.5 s (oracle `diarize_and_write` embeds all of them because `_rescue_short_segments` needs their vectors). Per segment, with `sr = 16000`, `short = 2 s = 32000` samples, `cap = 20 s = 320000` samples, `total` = PCM length:
  - empty PCM (`total <= 0`) → every embedding is all zeros;
  - `t0 = start · sr`, `t1 = end · sr` (truncated to integers; from stored ms this is exactly `ms · 16`);
  - past-end / empty segment (`t1 <= t0` or `t0 >= total`) → tail window: `t0 = max(0, total − short)`, `t1 = total`;
  - `dur = min(t1, total) − t0`; if `dur <= 0` → an all-zero 2 s window;
  - `dur < short` → a 2 s zero buffer with the segment's audio placed centered at offset `(short − dur) / 2` (never borrowing neighbouring audio); ported 1:1 from the oracle, and re-evaluated only during item 09's threshold tuning (consultation 2026-10-07);
  - `dur > cap` → centered 20 s slice: `mid = (t0 + t1) / 2`, `st = max(t0, mid − cap / 2)`, `en = min(t1, st + cap)`, slice `[st, en)` clipped to `total`;
  - otherwise the slice `[t0, t0 + dur)`.
- **L2 normalization** of each embedding; a zero-norm vector stays all zeros (oracle sets the norm to 1).
- **One window per inference call** (`[1, T, 80]`): the oracle's length-sorted batching (`EMBED_CHUNK`, `EMBED_BATCH_SECONDS`) is a PyTorch memory workaround, not behavior, and is not ported. Results are returned in segment order.
- **Pause gate and cancellation** checked between windows; inference runs on `Dispatchers.Default` (CPU-bound). Progress = windows done / total windows.
- **Test fixtures** (committed under `backend/src/test/resources/librispeech/` with their licence notice, ADR 0026; first fetched by URL + sha256 through Nix, changed 2026-10-09): two utterances of one speaker and one utterance of another from LibriSpeech test-clean (CC BY 4.0; the attribution is added to the repository) (consultation 2026-10-07).

## Open questions (answered 2026-10-07)

- Fixture clips for same/different-speaker tests? → **LibriSpeech test-clean utterances, fetched by hash** (CC BY 4.0, with attribution).
- Keep the oracle's 2 s zero-padding unchanged for CAM++? → **Yes, ported 1:1**; re-evaluated during item 09's threshold tuning.
- Intra-op threads of the ONNX session? → **`STT_THREADS` if set, else ONNX Runtime's default.**
- When is the effect of the 16-bit-quantized PCM (ADR 0024) on the embeddings measured? → **In item 09's threshold tuning**, by comparing embeddings from the rebuilt 16-bit PCM against an f32 decode of the same source.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-07 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Items that load the real CAM++ model are tagged `Integration` (ADR 0021).

- [x] Fbank of N samples yields `(N + 80) / 160` frames of 80 values (snip_edges false)
- [x] The povey window of length 400 matches hand-computed values at its start, center and end
- [x] Mel filterbank edges for 80 bins between 20 Hz and 7600 Hz at FFT size 512 match hand-computed bin positions
- [x] Silence and a pure tone produce the expected relative fbank energies (tone bin dominates)
- [x] Global-mean normalization makes every feature column average to zero
- [x] The ONNX Runtime native library loads from the JVM inside the devenv shell (moved from item 01)
- [x] Integration: loading the model at `$STT_SPEAKER_MODEL` reads its metadata (`output_dim` 512, `sample_rate` 16000, `normalize_samples` true, `feature_normalize_type` global-mean)
- [x] Unsupported model metadata (`sample_rate` other than 16000, `feature_normalize_type` other than global-mean, `output_dim` missing or not an integer) is rejected with an error naming the key and the value
- [x] The session uses `STT_THREADS` intra-op threads when it is set, and ONNX Runtime's default otherwise
- [x] A segment shorter than 2 s becomes a centered, zero-padded 2 s window
- [x] A segment longer than 20 s becomes the centered 20 s slice
- [x] A segment past the end of the audio uses the last 2 s (or the whole audio if shorter)
- [x] Empty audio yields all-zero embeddings for every segment
- [x] Embeddings have unit length; a zero vector stays zero
- [x] Embeddings are returned in segment order
- [x] Pausing stops embedding between windows and resuming continues with the next window
- [x] Integration: on the LibriSpeech fixture clips, same-speaker cosine similarity is higher than different-speaker cosine
- [x] (discovered) Fbank values of a fixed, reproducible test signal match a Kaldi reference (kaldi-native-fbank or torchaudio `compliance.kaldi.fbank` with the ADR 0003 options), reference values supplied by the user
- [x] (discovered) Loading configuration fails with an error naming `STT_SPEAKER_MODEL` when it is unset or empty; when set, its path is part of the configuration
- [x] (discovered) Model metadata with `sample_rate` or `normalize_samples` missing or not an integer, or `feature_normalize_type` missing, is rejected with an error naming the key and the value
- [x] (discovered) Integration: a short excerpt (600 ms starting 2 s into each LibriSpeech clip, so its window is zero-padded) is more similar to every whole clip of its own speaker than to the whole clip of the other speaker
- [x] (discovered) Integration: cancelling stops embedding at the next window boundary, with or without a pause gate
- [x] (discovered) Integration: the embedding of a fixed zero-padded window (1089-134686-0002 at 2000–2600 ms, centered in 32 000 samples) matches a sherpa-onnx reference (cosine ≥ 0.999; reference supplied by the user)

## Acceptance criteria

- Fbank unit tests pass against hand-computed values (frame count/shape, povey window, mel bank edges).
- Windowing tests cover every branch of oracle `embed_windows` listed above.
- Tagged `Integration` test with the real CAM++ model (`$STT_SPEAKER_MODEL`, sha256 from ADR 0003) shows same-speaker cosine > different-speaker cosine on the LibriSpeech fixture clips.
- `backend/deps.json` regenerated for ONNX Runtime and `nix build .` green, verified by the user.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-07: consultation decisions approved: fixture clips from LibriSpeech test-clean (CC BY 4.0, attribution in the repo), fetched by hash through Nix; the oracle's 2 s zero-padding is ported 1:1 and re-evaluated during item 09's threshold tuning; ONNX intra-op threads = `STT_THREADS` if set, else ONNX Runtime's default; the effect of the 16-bit-quantized PCM is measured in item 09's threshold tuning. Test list of 16 items approved with the spec. No new ADR: item-local decisions, recorded here.
- 2026-10-07: discovered test item confirmed: fbank values are compared with a Kaldi reference (items 1–4 check shape, window, mel edges and relative energies only, so a value-level deviation such as a constant scale would go unnoticed); the reference values are produced by the user outside the repository (no Python in the app, build or tests, ADR 0002) and committed as literals in the test.
- 2026-10-07: test item "Model metadata is read …; unsupported values are rejected" split into an `Integration` item (reading the real model's metadata) and a unit item (rejecting unsupported metadata), one behavior per test. `STT_SPEAKER_MODEL` becomes a required `Config` value (unset fails fast at startup, like `STT_DATA_DIR`); `Integration` tests obtain the model path through `Config.fromEnv`.
- 2026-10-07: discovered test item confirmed: loading configuration fails naming `STT_SPEAKER_MODEL` when it is unset or empty, and carries its path when set (presence only; a wrong path fails when the session is created). Done before the `Integration` metadata item, which reads the path through `Config`.
- 2026-10-07: discovered test item confirmed: `sample_rate` and `normalize_samples` missing or not an integer are rejected like `output_dim` (sherpa-onnx requires all four metadata keys); widened to also reject a missing `feature_normalize_type`.
- 2026-10-08: Kaldi reference for the discovered fbank item supplied by the user: kaldi-native-fbank (the front-end sherpa-onnx uses for this CAM++ export) with the ADR 0003 options, run outside the repository on 1600 integer samples `floor(8000 sin(2π·440n/16000) + 4000 sin(2π·3000n/16000) + 0.5)` (checksums: sum 0, sum of absolute values 8674848); frames 0, 5 and 9 (all 80 bins) committed as literals, tolerance 1e-3. The test passed on its first run (the front-end from items 1–4 already matched); its ability to fail was checked with two uncommitted mutations of `fbank` (power × 1.01 → frame 0 bin 0 off by ln 1.01; edge clamping instead of reflection → frame 0 bin 0 off by 1.37).
- 2026-10-08: acceptance criterion "The ONNX session is created once per application run" moved to item 11: nothing in item 08 creates the application-wide `SpeakerModel`; the engine (item 11) is where it is wired. Item 08 keeps one session per `Embeddings` instance, reused for every window and call.
- 2026-10-08: acceptance criterion "`backend/deps.json` regenerated for ONNX Runtime and `nix build .` green" verified by the user: `deps.json` regenerated (adds onnxruntime 1.30.0, drops the unused jna 5.9.0); `nix build .` first failed in `:compileKotlin` with a metaspace OOM that `main` had as well, fixed by [ADR 0025](../docs/decisions/0025-nix-build-gradle-default-jvm-memory.md) (Gradle's default JVM memory in the Nix build); after the rebase onto `main` with item 07, `nix build .` is green (backend check + install, app wrapper).
- 2026-10-08: LibriSpeech fixtures: test-clean is only distributed as one 346 MB archive (openslr md5 32fa31d27d2e1cad72775fee3f4849a9 matched), so `nix/test-fixtures.nix` fetches it by sha256 and extracts three unmodified utterances into one directory (`TEST_LIBRISPEECH_CLIPS`): `1089-134686-0002` (6.6 s) and `1089-134691-0023` (7.7 s), speaker 1089 from two chapters, and `1188-133604-0025` (7.5 s), speaker 1188, both male, so the different-speaker pair is not separated by sex alone; all between 2 s and 20 s, so each window is the whole clip. Attribution: `docs/attributions.md`. Result with the real model: same-speaker cosine 0.801, different-speaker cosines 0.240 and 0.188. The test's red run was the missing fixture (the embedder already existed); it fails when speaker information is destroyed (all-zero features: 0.9997 vs 0.9999), and still passes with reversed mel bins (0.678 vs 0.649 / 0.640) or without mean subtraction (0.818 vs 0.485 / 0.402): it checks the ordering only, value-level front-end defects are covered by the fbank items.
- 2026-10-09: the Nix-built fixture directory (`/nix/store/7k0vbq8f…-librispeech-test-clean-clips`, from the user's devenv shell) holds exactly the three clips, byte-identical (sha256) to the ones the test was first run on; `gradle check` and `gradle integrationTest` green against it. All test-list items ticked; set to in-review.
- 2026-10-09: user decision during review: the backend convention "never fixtures committed to the repo" is removed and replaced by no rule ([ADR 0026](../docs/decisions/0026-no-rule-for-test-fixture-location.md)): a fixture that is easy to add as a Nix input is fetched through Nix, otherwise it is committed, with whatever its licence requires. Extracting 400 KB of clips needed the 346 MB archive in the Nix store, so the three LibriSpeech clips (byte-identical to the Nix-built ones) are committed under `backend/src/test/resources/librispeech/` with LibriSpeech's licence notice (CC BY 4.0) and read from the test classpath; the Nix fetch, `TEST_LIBRISPEECH_CLIPS` and the `devenv.nix` export are dropped. Item 07's Nix fixtures stay unchanged.
- 2026-10-09 (review round 1): item reopened; findings: (1, high) the model input scale is inverted: `Embeddings` multiplies by 32768 when `normalize_samples` is true, sherpa-onnx does so only when it is false (3D-Speaker models expect [-1, 1]); global-mean normalization hides it for windows full of sound, but it distorts every zero-padded (< 2 s) window; (2, medium) the packaged app no longer starts without `STT_SPEAKER_MODEL`, which the `flake.nix` wrapper does not set; (3, low, important to the user) without a pause gate, cancellation is not checked between windows. User decisions: 1 → fix, with both a LibriSpeech short-excerpt test and a sherpa-onnx reference test (reference values produced by the user outside the repository); 2 → the wrapper gets `--set-default STT_SPEAKER_MODEL` from `nix/models.nix` (item 16 may revisit); 3 → fix.
- 2026-10-09 (review round 1): discovered items for findings 1 and 3 confirmed: 1a short-excerpt ordering (red today), 3 cancellation at window boundaries, 1b sherpa-onnx reference of one fixed window. Order 1a → 3 → 1b; finding 2 first. The 1b reference was produced by the user outside the repository with sherpa-onnx `SpeakerEmbeddingExtractor` (CAM++ sha256 357a834f…4129b; input checksums: clip 106000 samples, segment sum −38678, sum of absolute values 8935266) and is committed as 512 literals in the test; tolerance cosine ≥ 0.999 (chosen by the agent at the user's request).
- 2026-10-09 (review round 1, cycle 1a): red — `gradle integrationTest --tests 'stt.diarization.EmbeddingsSpec'`: 5 tests, 1 failed: "excerpt of 1089-134686-0002: own-speaker cosines [0.2596548, 0.1405773], other-speaker cosines [0.1638302]; 0.1405773 should be > 0.1638302". Green: samples go in unchanged when `normalize_samples` is true and × 32768 otherwise (sherpa-onnx `features.cc`), Embeddings KDoc corrected; `gradle check` (105 unit tests) and `gradle integrationTest` (42 run) green.
- 2026-10-09 (review round 1): refactor approved and done after cycle 1a: shared `LIBRISPEECH_IDS`, `decodeLibrispeech` and `wholeClip` helpers in `EmbeddingsSpec`.
- 2026-10-09 (review round 1, cycle 3): red — `gradle integrationTest --tests 'stt.diarization.EmbeddingsSpec'`: 6 tests, 1 failed: "without a pause gate: expected:<[1]> but was:<[1, 2, 3]>" (the test cancels inside the window-1 callback; with a gate it already stopped, since the gate's suspension point is cancellable). Green: `embed` checks cancellation (`ensureActive`) before every window; `gradle check` (105 unit tests) and `gradle integrationTest` (43 run) green.
- 2026-10-09 (review round 1): refactor approved and done after cycle 3: shared `tone440` signal for the pause and cancel tests.
- 2026-10-09 (review round 1, cycle 1b): the reference is committed as a text block of the 512 values exactly as printed (one `floatArrayOf` argument per line would be 512 lines under ktlint's argument-list rule); the test first checks the user's input checksums (106 000 samples, segment sum −38 678, sum of absolute values 8 935 266). It passed on its first run, as expected after cycle 1a (measured cosine 1.0000001, i.e. identical up to float rounding). Ability to fail: with the × 32768 put back for `normalize_samples = true` (uncommitted mutation) it failed with "0.45896325 should be >= 0.999" (and cycle 1a's test failed again); code restored. `gradle check` (105 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09 (review round 1): refactor approved and done after cycle 1b: one shared `floats(text)` reference-value parser (`ReferenceValues.kt`) for `FbankSpec` and `EmbeddingsSpec`.
