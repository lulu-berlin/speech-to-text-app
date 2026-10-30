# 83 — Diarization accuracy: fewer wrongly merged and wrongly split speakers in automatic detection

Status: spec-approved
Depends on: [73](73-diarization-speaker-separation.md) (the split action: the reference labels are
made by correcting the app's output, which needs split as well as merge)
Blocks: —

## Goal

The speakers the app detects on its own match the people who actually spoke more often: distinct
speakers collapse under one id less often (over-merging) **and** one speaker is spread over several
ids less often (over-splitting). Both are measured on a labelled set of the user's real recordings
against today's pipeline, and only changes the measurement supports are adopted.

## Evidence

- **User report 2026-10-14**: diarization sometimes puts distinct speakers under one id and
  sometimes splits one speaker across ids; re-running reproduces the same result for the same input.
- **Today's pipeline** (one decision per stage, all hard-coded):
  - one CAM++ embedding per whisper segment (`diarization/Embeddings.kt:22-39`, ADR 0003), taken
    over one window per segment: shorter than 2 s zero-padded with the segment centered, longer than
    20 s cut to the centered 20 s (`diarization/EmbeddingWindows.kt:4,7,24`);
  - segments shorter than 0.5 s are not clustered and are later rescued to the nearest speaker
    centroid (`diarization/SpeakerIds.kt:10-13,36,51-54`);
  - average-linkage (UPGMA) clustering on cosine distance (`diarization/Clustering.kt:20`, ADR 0037);
  - the speaker count: one speaker when the last merge is below `SPEAKER_DISTANCE_THRESHOLD = 0.5448`
    (`Clustering.kt:11,33`, ADR 0032), otherwise the largest gap among the last `maxSpeakers` merge
    heights (`Clustering.kt:35,41-44`, ADR 0027).
- **The threshold was calibrated on studio speech**: 18 LibriSpeech clips of 6 readers (ADR 0032),
  not on recordings like the user's.
- **A segment has exactly one speaker.** Clustering labels whole whisper segments, so a speaker change
  inside one segment cannot be represented: that segment's speech is attributed to one person, which
  looks like over-merging. whisper-cli runs without word-level or maximum-length options
  (`transcribe/WhisperCommand.kt:18`), so a segment can span a turn change — `[inference]`, to be
  measured on the labelled set.
- **Long segments are judged by their middle**: past 20 s only the centered 20 s are embedded, so a
  second speaker outside that window is invisible to clustering.
- **Short segments get weak embeddings**: a 0.5–2 s segment is mostly zero padding inside its 2 s
  window, a plausible source of wrong assignments in both directions — `[inference]`, to be measured.
- **Diagnostics exist**: `diarization/SpeakerSummary.kt` logs each speaker's segment count and
  within-speaker cosine similarity, useful for explaining a score but not a score itself.
- **Fixed-count mode** (per upload) is today's workaround when the automatic count is wrong.
- **The embedding model is one of a family**: today's CAM++ export comes from sherpa-onnx's
  speaker-recognition release (`nix/models.nix:14-17`), which also carries 3D-Speaker's ERes2Net,
  WeSpeaker's ResNet and NeMo's TitaNet exports, each under its own licence
  (<https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models>). The models'
  cosine distances differ, so each needs its own threshold, and only those sharing CAM++'s Kaldi
  fbank front-end are drop-ins for `Embeddings.kt` — `[inference]`, to be checked per model.
- **Speaker-segmentation models exist**: `pyannote/segmentation-3.0` (MIT licence; gated on Hugging
  Face, also exported to ONNX by sherpa-onnx) detects speaker changes and overlapped speech in the
  audio itself, independent of whisper's segments
  (<https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html>). It is the model-level
  answer to "one speaker per whisper segment".
- This work was the second half of item 73 until 2026-10-29; there its only success measure
  ("separates the speakers in a measured share of the cases that fail today") covered
  over-merging, and the named metric (cluster purity) cannot see over-splitting.

## Decided (carried over from item 73's consultation, 2026-10-28)

- **The user's recordings stay local**: only the metric definitions, aggregate scores and
  synthetic/licensed clips are committed; the LibriSpeech fixtures remain the controls.
- **The levers are decided by the measured scores** on that set, not in advance.

## Decided (consultation, 2026-10-29)

- **The labelled set lives in `.devenv/state/stt-eval/`** (the user): already git-ignored by
  `.devenv*`, inside the checkout the agents' sandbox can read; no new ignore rule.
- **Success is both directions down, with a bounded regression** (the user): over-merge and
  over-split counts both go down (or one stays equal), speaker confusion improves, and no recording
  gets worse by more than a tolerance. The target and the tolerance are fixed from the baseline,
  with the user, before any lever is tried.
- **Speaker changes inside one whisper segment: measure first** (the user): the baseline reports how
  often a segment spans two reference speakers, and the segmentation model is compared from the start
  (below). Whether to split stored segments is decided after those numbers, possibly as its own item;
  until then this item does not change stored segments.
- **Runtime budget: up to +50%** diarization time on the labelled set (the user).
- **The per-task sensitivity control is decided after the measurement** (the user): only if the
  automatic rule cannot be fixed well enough.
- **New tasks only** (the user): existing tasks keep their speakers; a re-diarize action would be
  a separate item.
- **After 1.0** (the user): not a release gate of item 78.
- **e2e: a two-speaker journey test** (the user, ADR 0046): the journey's two-speaker clip (item 09's
  fixture: LibriSpeech speakers 1089, 1188, 1089 back to back, `e2e/lib/fixtures.ts:150`) comes out
  as exactly two speakers with their three turns.
- **Python is allowed for the exploration only, and removed once the results are in** (the user):
  model comparisons, segmentation trials and fine-tuning may use Python. The code lives in the
  git-ignored `.devenv/state/stt-eval/` (item 75's guard skips `.devenv`, so the docs checks stay
  green), is never committed, and is deleted when the results are recorded. The app, its build and
  its tests stay Python-free (AGENTS.md, ADR 0063); whatever is adopted runs in Kotlin on ONNX
  Runtime (ADR 0003), and the final numbers come from the Kotlin harness on the shipped pipeline.
  This exception to ADR 0063 is recorded in this item's ADR.
- **The exploration's Python environment is a Nix shell file in `.devenv/state/stt-eval/`** (the
  user): never committed, entered by whoever runs the scripts; `devenv.nix` stays untouched.
- **Fine-tuning runs on the user's machine** (the user): the agent prepares the scripts and the exact
  commands, the user runs them (GPU) and reports the results and where the exported ONNX file is.
- **A model fine-tuned on the user's recordings is a local override only** (the user): used by the
  user's own install through `STT_SPEAKER_MODEL`, never committed, pinned or published. Everyone else
  gets the best published model the comparison finds.
- **The comparison is broad, and pyannote is in it from the start** (the user): embedding models from
  each family in the release — 3D-Speaker, WeSpeaker and NeMo, including NeMo's different front-end —
  but not necessarily every export; `pyannote/segmentation-3.0` is compared from the first trials.
- **The reference labels are the app's output, corrected** (the user, 2026-10-30): the user corrects
  each recording's diarization in the transcript view (merge, split, rename), a Kotlin tool in the
  backend's test sources exports it to one `speaker start end` line per turn, and the user then
  splits each mixed segment by hand in the exported file at the speaker change. The labels keep the
  changes inside whisper segments, so the mixed-segment share and the segmentation model stay
  measurable. The number of recordings and the held-out part are chosen with the user when the set
  is built.

## Proposal

1. **A labelled local set and a scoring harness.** The user's recordings with reference speaker
   labels (who spoke from when to when) stay local, in a git-ignored location inside the repository,
   and are never committed. A scoring tool in the backend's test sources (Kotlin; no Python, ADR 0063)
   scores a diarization against the reference: diarization error rate's speaker-confusion part (with
   a collar around turn boundaries), purity (how wrongly merged) and coverage (how wrongly split), and
   per recording the over-merge and over-split counts. Only the metric definitions, synthetic clips
   and the aggregate scores are committed.
2. **A baseline**: today's pipeline scored on the set, recorded before anything changes.
3. **Levers, each measured against the baseline**, adopted only when the numbers support them:
   - the count rule: the threshold and the gap window recalibrated on the set;
   - short segments: the window minimum and its padding, the 0.5 s clustering cutoff, the rescue
     rule;
   - long segments: several windows across the segment instead of the centered 20 s, so its
     embedding represents all of it;
   - speaker changes inside one segment: measured in the baseline; acting on them is decided after it
     (see Decided);
   - clustering: a re-assignment pass after the cut (each segment to its nearest speaker centroid),
     or another linkage;
   - **another embedding model**: the candidates from the same ONNX family, each scored with its
     own recalibrated threshold; adoption needs a licence that permits redistribution, a size and a
     runtime within the budget, a front-end `Embeddings.kt` can run, and a pin in `nix/models.nix`
     and `versions.json` (a new model supersedes ADR 0003's choice);
   - **a speaker-segmentation model** (`pyannote/segmentation-3.0`) that finds speaker changes
     inside whisper segments, compared from the first trials (see Decided);
   - **adapting one model to the recordings**: first without retraining (embedding centering,
     whitening or LDA/PLDA, a few parameters learned from embeddings, applied in Kotlin); then, if
     the numbers point there, fine-tuning the model's weights in the exploration, exported back to
     ONNX. Fine-tuning needs held-out recordings it never trained on, and a model trained on the
     user's recordings is a local override only (see Decided).
4. **Runtime**: diarization time on the set recorded for every adopted change, within +50%.
5. **An ADR** records the metrics, the baseline, the chosen configuration and the before/after.

## Open questions

None. (All settled 2026-10-29/30, see "Decided (consultation, 2026-10-29)".)

## Test list (approved; behaviors only)

- [ ] The scoring harness scores a diarization against reference labels — speaker confusion, purity,
      coverage, over-merge and over-split counts — matching hand-derived scores on a synthetic case
- [ ] Today's pipeline is scored on the labelled local set: the aggregate baseline, including the
      share of whisper segments that span two reference speakers, is recorded, and no recording or
      label file is tracked
- [ ] Each compared configuration — embedding models from every family with their own calibrated
      thresholds, the segmentation model, and adaptation of one model — is scored on the same set
      with the same harness, and its aggregate scores and runtime are recorded
- [ ] The adopted configuration, run by the app's own Kotlin pipeline, improves the labelled set by
      the agreed target in both directions (over-merging and over-splitting) and makes no recording
      worse beyond the agreed tolerance
- [ ] A locally fine-tuned model, when one is produced, runs as the user's `STT_SPEAKER_MODEL`
      override and is scored on recordings it was not trained on; it is never tracked or published
- [ ] The LibriSpeech controls keep the separation they have today
- [ ] Diarization runtime on the labelled set stays within +50% of the baseline
- [ ] (e2e, ADR 0046) The journey's two-speaker clip comes out as exactly two speakers with their
      three turns
- [ ] When the results are recorded, no Python file is tracked and the exploration's Python is
      deleted from `.devenv/state/stt-eval/`
- [ ] (must not break) Fixed-count mode, rename, merge and split, and the existing diarization suites
      stay green

## Acceptance criteria

- The baseline, every compared configuration and the adopted one are measured on the labelled set in
  both directions; the adopted configuration meets the agreed target and runs in the app's Kotlin
  pipeline; an ADR records the numbers, the choice and the exploration-only Python exception.
- No recording, label file, Python file or model trained on the user's recordings is committed or
  published.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-29: filed as a draft by the root agent at the user's request, from the detection half of
  item 73 (moved here) and the review of the pipeline's limits (one speaker per whisper segment, the
  centered 20 s window, padded short windows, a studio-calibrated threshold). Not yet discussed.
- 2026-10-29/30: consultation with the user: labelled set in `.devenv/state/stt-eval/`; success is
  both directions down with a tolerance fixed from the baseline; +50% runtime; mixed segments
  measured first; sensitivity control after the measurement; new tasks only; after 1.0; a
  two-speaker e2e; a broad model comparison with pyannote from the start; adaptation and fine-tuning
  as levers, fine-tuning on the user's machine with a local-only result; exploration-only Python in
  a Nix shell in the ignored directory, deleted after the results; reference labels by correcting
  the app's output, exported and hand-split at mixed segments (so the item depends on item 73's
  split). Test list approved as written; status set to spec-approved.
