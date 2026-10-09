# 09 — Diarization

Status: draft
Depends on: [08](../docs/specs/speaker-embeddings.md) (done)

## Goal

Assign a speaker index to every transcript segment of a task in pure Kotlin, reproducing the oracle's clustering, short-segment rescue, turn merging, and diagnostics (`docs/oracle/transcribe.py`), with the n == 2 threshold re-tuned for the CAM++ embeddings produced by item 08.

## Already decided

- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): clustering (UPGMA + largest-gap speaker count), short-segment rescue, and turn merging are ported 1:1; the oracle's 0.35 n == 2 threshold is re-tuned for CAM++.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): `docs/oracle/transcribe.py` is read, never run.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): in-JVM phases pause cooperatively via a pause gate.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole-file PCM is always rebuilt from the task's stored 16-bit FLAC chunks (item 11), so speaker labels do not depend on restarts; the n == 2 threshold is tuned on PCM built the same way.

## Proposal (pending approval)

1:1 Kotlin ports of the oracle functions below, as pure functions over an embedding matrix (rows L2-normalized, from item 08) plus the per-task orchestration of `diarize_and_write` minus the file writing (item 10 renders outputs).

- **Orchestration (oracle `diarize_and_write`).**
  - Input: all segments of the task in transcript order (across chunks), `speakers_mode`/`speakers_count`, `max_speakers`; the whole-file PCM, rebuilt by item 11 from the task's stored FLAC chunks (ADR 0024; 16-bit-quantized, unlike the oracle's float32 decode of the original).
  - Clustered set `idxs` = segments with `end - start >= 0.5 s` (`SHORT_SEGMENT_SECONDS`); if that set is empty, all segments are clustered.
  - Embeddings are computed for **all** segments (item 08 `embed_windows` port), because short segments need their own embedding for the rescue step.
  - `k` = `null` for `auto`, else the fixed count; `cluster_segments(embs[idxs], k, max_speakers)`; unclustered segments start at label 0; then `_rescue_short_segments`; then `_print_speaker_summary` on the clustered rows with their final labels.
  - Output: one speaker index (0-based) per segment, persisted into `segments.speaker` (item 03); the renderers add 1 for display.
  - Not called when `speakers_mode = off` or when the task has no segments (oracle `transcribe_one` skips diarization when `parse_segments` returns nothing).
- **`cluster_segments` (UPGMA).**
  - `n <= 1` → all labels 0.
  - Distance `D = 1 - E·Eᵀ` (cosine distance, rows already unit length), diagonal = +∞.
  - Repeat until one active cluster: pick the minimum of `D` restricted to active clusters (first minimum in row-major order over the active list, which keeps original index order, so `i` precedes `j`); record height `D[i, j]` and merge `(i, j)`; new row for `i` = `(size_i·D[i,·] + size_j·D[j,·]) / (size_i + size_j)` (unweighted average); `size_i += size_j`; `D[i,i]` and row/column `j` set to +∞; drop `j` from the active list.
  - Auto `k` (`k == null`): if `n == 2`, `k = 1` when the single merge height `< threshold` (oracle: 0.35, to be re-tuned) else 2. Otherwise `gaps = diff(heights)`, search only the last `max_speakers` gaps (`start = max(0, gaps.size - max_speakers)`), `idx` = first argmax of `gaps[start:]` + `start`, `k = n - (idx + 1)`, clamped to `[1, max_speakers]`.
  - Fixed `k`: clamped to `[1, n]` (not to `max_speakers`).
  - `k == 1` → all labels 0. Otherwise apply the first `n - k` merges with union-find, then relabel the roots in order of first appearance by segment index (labels `0..k-1`).
- **`_rescue_short_segments`.**
  - `ok` = segments with `end - start >= 0.5 s`, recomputed here (independent of the "cluster everything" fallback); if `ok` is empty, labels are returned unchanged.
  - For each cluster `c` in `0..max(labels)`: centroid = L2-normalized sum of embeddings of `ok` segments labelled `c`; zero vector if it has none (a zero sum also stays zero).
  - Each segment not in `ok` gets the label of the non-zero centroid with the highest dot product with its own embedding (first max on ties) — any speaker, not only its neighbours; if no centroid is non-zero, the label is left unchanged.
  - No relabelling afterwards (a rescued segment may make a higher-numbered speaker appear first; kept as in the oracle).
- **`speaker_turns`.** Consecutive segments with the same speaker collapse into one turn `(label "Speaker N" with N = index + 1, start of the first segment, end of the last, texts trimmed and joined with single spaces, then trimmed)`. Used by item 10 for srt/vtt; lives here because it is speaker logic.
- **`_print_speaker_summary` → log.** Over the clustered rows with final labels: per present cluster (ascending) one line `speaker {c+1}: {n} segments, within-cos {wc:.3f}` where `wc` = mean off-diagonal pairwise cosine (1.0 for a single member); if `max label + 1 > 1`, one line `cluster-to-cluster cosine: ` followed by `{a+1}-{b+1} {mean cross cosine:.3f}` per pair `a < b`, joined by two spaces. Logged at INFO; any exception in the summary is caught and logged, never fails the task.
- **Pause gate / cancellation.** The pause gate (item 08) is honoured between embedding windows; clustering itself is CPU-bound and short, run on `Dispatchers.Default` with cancellation checks between merge iterations.
- **Threshold re-tuning.** The n == 2 threshold for CAM++ is chosen from distances measured on fixture clips (same-speaker vs different-speaker pairs) and recorded, with the measurements, in a new ADR (ADR 0003 already states that it is re-tuned).

## Open questions for the user

- Threshold tuning procedure and acceptance: which fixture clips, how many pairs, and which rule picks the value (e.g. midpoint between the highest same-speaker and lowest different-speaker distance)?
- Maximum speakers limit in the UI (oracle default `MAX_SPEAKERS = 8`; upper bound for the `maxSpeakers` field and for fixed `N`?).
- Oracle quirk to confirm: in auto mode with `n >= 3` clustered segments the largest-gap rule can only yield `k` in `[2, max_speakers]`, so a single-speaker recording with three or more segments is always split. Port 1:1 as ADR 0003 states, or address it with a new ADR?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Zero or one embedding yields all labels 0
- [ ] Two embeddings closer than the threshold are one speaker; farther than the threshold are two speakers (auto)
- [ ] Auto mode picks the speaker count at the largest gap among the last `max_speakers` merge heights (hand-built matrix with three well-separated groups yields three speakers)
- [ ] Auto mode never returns more than `max_speakers` speakers
- [ ] Fixed `k` returns exactly `k` speakers, clamped to the number of segments; fixed `k = 1` returns all 0
- [ ] UPGMA merge heights equal hand-computed average-linkage distances for a small matrix
- [ ] Labels are numbered by first appearance in segment order
- [ ] Segments shorter than 0.5 s are excluded from clustering and do not create or take a speaker slot
- [ ] When every segment is shorter than 0.5 s, all segments are clustered and the rescue step leaves labels unchanged
- [ ] A short segment is rescued to the nearest centroid, including a speaker that is not its neighbour in time
- [ ] Clusters with no long members are ignored by the rescue step
- [ ] Consecutive same-speaker segments merge into one turn with first start, last end, and space-joined trimmed text
- [ ] The speaker summary logs per-cluster within-cosine and pairwise cluster-to-cluster cosine; a failure in the summary does not fail diarization
- [ ] Pausing during diarization stops between embedding windows and resumes where it stopped; cancelling stops it

## Acceptance criteria

- For hand-built embedding matrices, the Kotlin labels equal the labels derived by hand from the oracle algorithm (auto k, fixed k, n = 1, n = 2 on both sides of the threshold, rescue cases).
- The re-tuned n == 2 threshold is recorded in a new ADR with the measurements it was derived from.
- On the fixture clips (tagged `Integration`), a two-speaker recording is labelled with two speakers and a turn boundary at the speaker change.
- `segments.speaker` is populated for every segment of a diarized task; tasks with `speakers = off` leave it NULL.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
