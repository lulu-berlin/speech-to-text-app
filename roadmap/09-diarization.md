# 09 — Diarization

Status: in-progress
Depends on: [08](../docs/specs/speaker-embeddings.md) (done)

## Goal

Assign a speaker index to every transcript segment of a task in pure Kotlin, reproducing the oracle's clustering, short-segment rescue, turn merging, and diagnostics (`docs/oracle/transcribe.py`), with the speaker-count threshold re-tuned for the CAM++ embeddings produced by item 08 and applied to every automatic count ([ADR 0027](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md)).

## Already decided

- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): clustering (UPGMA + largest-gap speaker count), short-segment rescue, and turn merging are ported 1:1; the oracle's 0.35 threshold is re-tuned for CAM++.
- [ADR 0027 — Automatic speaker count allows one speaker](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md): amends ADR 0003's speaker-count rule: one speaker whenever the last merge is below the threshold, not only for two segments.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): `docs/oracle/transcribe.py` is read, never run.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): in-JVM phases pause cooperatively via a pause gate.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole-file PCM is always rebuilt from the task's stored 16-bit FLAC chunks (item 11), so speaker labels do not depend on restarts; the threshold is tuned on PCM built the same way.
- [ADR 0026 — No rule for where test fixtures live](../docs/decisions/0026-no-rule-for-test-fixture-location.md): additional LibriSpeech clips are committed like item 08's, with their licence notice and attribution.
- [Item 08 spec](../docs/specs/speaker-embeddings.md): `Embeddings.embed` (one L2-normalized 512-d vector per segment, pause gate and cancellation between windows); its Approval log defers the 2 s zero-padding re-evaluation and the 16-bit measurement to this item.

## Proposal (approved 2026-10-09)

1:1 Kotlin ports of the oracle functions below, except the automatic speaker count (ADR 0027), plus the per-task orchestration of `diarize_and_write` minus the file writing (item 10 renders outputs).

- **Orchestration (oracle `diarize_and_write`).**
  - Input: all segments of the task in transcript order (across chunks), `speakers_mode`/`speakers_count`, `max_speakers`; the whole-file PCM, rebuilt by item 11 from the task's stored FLAC chunks (ADR 0024; 16-bit-quantized, unlike the oracle's float32 decode of the original).
  - A segment is **long** when `endMs − startMs >= 500` (oracle `SHORT_SEGMENT_SECONDS` = 0.5 s), compared in whole milliseconds: exact, unlike the oracle's float subtraction, which can classify a segment of exactly 0.5 s as short.
  - Clustered set `idxs` = the long segments; if that set is empty, all segments are clustered.
  - Embeddings are computed for **all** segments (item 08), because short segments need their own embedding for the rescue step.
  - `k` = `null` for `auto`, else the fixed count; `cluster_segments(embs[idxs], k, max_speakers)`; unclustered segments start at label 0; then `_rescue_short_segments`; then `_print_speaker_summary` on the clustered rows with their final labels.
  - The labelling (clustering, rescue, summary) is a pure function of the segments, their embedding matrix and the speaker settings, so it is tested with hand-built embeddings; only the real-model scenarios are `Integration` tests.
  - Output: one speaker index (0-based) per segment; the renderers add 1 for display.
  - Not called when `speakers_mode = off` or when the task has no segments (oracle `transcribe_one` skips diarization when `parse_segments` returns nothing); that decision and its effect (`segments.speaker` stays NULL) belong to the engine (item 11).
- **Persistence.** A new `stt/db` operation stores the speaker of every segment of a task in one write transaction: all or nothing; a label count that differs from the task's segment count fails and stores nothing.
- **`cluster_segments` (UPGMA).**
  - `n <= 1` → all labels 0.
  - Distance `D = 1 - E·Eᵀ` (cosine distance, rows already unit length), computed in double, diagonal = +∞.
  - Repeat until one active cluster: pick the minimum of `D` restricted to active clusters (first minimum in row-major order over the active list, which keeps original index order, so `i` precedes `j`); record height `D[i, j]` and merge `(i, j)`; new row for `i` = `(size_i·D[i,·] + size_j·D[j,·]) / (size_i + size_j)` (unweighted average); `size_i += size_j`; `D[i,i]` and row/column `j` set to +∞; drop `j` from the active list.
  - Auto `k` (`k == null`, ADR 0027): if the last (highest) merge height `< threshold` → `k = 1`. Otherwise, if `n == 2` → `k = 2`; else `gaps = diff(heights)`, search only the last `max_speakers` gaps (`start = max(0, gaps.size - max_speakers)`), `idx` = first argmax of `gaps[start:]` + `start`, `k = n - (idx + 1)`, clamped to `[1, max_speakers]`. For `n == 2` this is exactly the oracle's rule.
  - Fixed `k`: clamped to `[1, n]` (not to `max_speakers`).
  - `k == 1` → all labels 0. Otherwise apply the first `n - k` merges with union-find, then relabel the roots in order of first appearance by segment index (labels `0..k-1`).
  - **Performance:** ported as the oracle has it, `O(n²)` memory for `D` and `O(n³)` time. Estimated (not measured) for ~3000 segments (~3 h of audio): a 72 MB matrix and seconds of CPU; ~5000 segments: 200 MB and up to a minute. Time and memory are measured at 1000 and 3000 segments and recorded in the Approval log. **If performance becomes an issue, this is revisited** (for example the nearest-neighbor-chain algorithm, `O(n²)` time, whose merge order can differ from the oracle's on tied distances), with its own decision.
- **`_rescue_short_segments`.**
  - `ok` = the long segments, recomputed here (independent of the "cluster everything" fallback); if `ok` is empty, labels are returned unchanged.
  - For each cluster `c` in `0..max(labels)`: centroid = L2-normalized sum of embeddings of `ok` segments labelled `c`; zero vector if it has none (a zero sum also stays zero).
  - Each segment not in `ok` gets the label of the non-zero centroid with the highest dot product with its own embedding (first max on ties) — any speaker, not only its neighbours; if no centroid is non-zero, the label is left unchanged.
  - No relabelling afterwards (a rescued segment may make a higher-numbered speaker appear first; kept as in the oracle).
- **`speaker_turns`.** Consecutive segments with the same speaker collapse into one turn: label "Speaker N" with N = index + 1, start of the first segment, end of the last, texts trimmed and joined with single spaces, then trimmed. Each turn also **keeps its segments, each with its own start, end and text**: minimal groundwork for a possible later playback view that highlights the segment being played (consultation 2026-10-09; the view itself is not part of any roadmap item yet). Used by item 10; lives here because it is speaker logic.
- **`_print_speaker_summary` → log.** Over the clustered rows with final labels: per present cluster (ascending) one line `speaker {c+1}: {n} segments, within-cos {wc:.3f}` where `wc` = mean off-diagonal pairwise cosine (1.0 for a single member); if `max label + 1 > 1`, one line `cluster-to-cluster cosine: ` followed by `{a+1}-{b+1} {mean cross cosine:.3f}` per pair `a < b`, joined by two spaces. Logged at INFO; any exception in the summary is caught and logged, never fails the task.
- **Pause gate / cancellation.** Embedding pauses and cancels between windows (item 08). Clustering runs on `Dispatchers.Default` and checks the pause gate and cancellation between merge iterations, since a long recording takes seconds.
- **Threshold tuning** (one threshold, used by the ADR 0027 rule).
  - Fixtures: more LibriSpeech test-clean utterances committed next to item 08's (ADR 0026; licence notice and `docs/attributions.md` updated): about 6 speakers of mixed sex, 3 utterances each, from different chapters where possible, each between 2 s and 20 s.
  - Measurement: all same-speaker and different-speaker pair distances with the app's embedder, on whole clips and on excerpts of typical segment lengths (1.5–8 s). LibriSpeech is 16-bit FLAC, so decoding it equals the app's rebuild from 16-bit FLAC chunks (ADR 0024).
  - Rule: the midpoint between the highest same-speaker and the lowest different-speaker distance; if the two ranges overlap, the value with the fewest misclassified pairs.
  - The measurement runs as a throwaway program; only its numbers are kept. The value, the clip list and the measurements are recorded in a new ADR (as ADR 0003 requires); a permanent `Integration` test checks the chosen value on the fixture pairs.
- **Deferred from item 08.**
  - 2 s zero-padding: short-segment similarities are measured with the 2 s padding, a shorter minimum window, and no padding, and recorded in the threshold ADR; the behavior changes only if the numbers clearly favor it, with its own ADR.
  - 16-bit quantization: measured only if quick (the user expects no relevant effect on speech): a LibriSpeech clip encoded to a lossy format, embedded from its float32 decode and from its 16-bit FLAC rebuild; numbers in the threshold ADR.

## Open questions (answered 2026-10-09)

- Oracle quirk: in auto mode with `n >= 3` clustered segments the largest-gap rule only yields `k` in `[2, max_speakers]`, so a single speaker is always split. → **Fix it** ([ADR 0027](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md)): one speaker whenever the last merge is below the threshold.
- Threshold tuning procedure and acceptance? → **About 6 LibriSpeech speakers × 3 utterances, whole clips and excerpts, midpoint rule (fewest errors on overlap), throwaway measurement, value in a new ADR, permanent check on the fixture pairs.**
- The deferred 16-bit measurement? → **Only if quick**; the user expects no relevant effect on speech.
- Maximum speakers limit in the UI (oracle default `MAX_SPEAKERS = 8`)? → **Moved to items 12 (input validation) and 14 (UI)**; this item's algorithm accepts any `max_speakers >= 1`.
- Performance on long recordings? → **Port as the oracle has it**, measure at 1000 and 3000 segments, and revisit if performance becomes an issue.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Unless marked `Integration`, the scenarios use hand-built embeddings.

- [x] Zero or one clustered segment yields all labels 0
- [x] Two segments: a merge height below the threshold yields one speaker, at or above it two speakers (auto)
- [x] UPGMA merge heights equal hand-computed average-linkage distances for a 4-point matrix
- [x] On tied distances, the lower-index pair is merged first
- [x] Auto mode: three well-separated groups yield three speakers
- [x] Auto mode looks for the largest gap only among the last `max_speakers` merges; a larger gap lower in the tree is ignored
- [x] Auto mode never returns more than `max_speakers` speakers
- [x] Auto mode: when even the last merge is below the threshold, one speaker (ADR 0027)
- [x] Fixed `k` returns exactly `k` speakers, clamped to `[1, n]`; fixed `k = 1` returns all 0; `max_speakers` is ignored
- [ ] Labels are numbered by first appearance in segment order
- [ ] Segments shorter than 500 ms are not clustered and neither create nor take a speaker slot; a segment of exactly 500 ms is clustered
- [ ] When every segment is shorter than 500 ms, all are clustered and the rescue step leaves labels unchanged
- [ ] A short segment is rescued to the nearest centroid, including a speaker that is not its neighbour in time
- [ ] The speakers of all segments of a task are stored in one transaction; a label count that differs from the segment count stores nothing
- [ ] Consecutive same-speaker segments merge into one turn (first start, last end, trimmed texts joined with single spaces) that keeps each of its segments with its own start, end and text; a speaker change starts a new turn
- [ ] The speaker summary logs per-cluster within-cosine (1.0 for a single member) and pairwise cluster-to-cluster cosine; a failure in the summary does not fail diarization
- [ ] Clustering pauses between merge iterations and resumes; cancelling stops it at the next iteration
- [ ] Integration: LibriSpeech clips concatenated as speaker 1089 → 1188 → 1089 are labelled `[0, 1, 0]`, with turn boundaries at the clip changes
- [ ] Integration: on the fixture pairs, same-speaker distances fall below the tuned threshold and different-speaker distances are at or above it

## Acceptance criteria

- For hand-built embedding matrices, the Kotlin labels equal the labels derived by hand from the algorithm above (auto `k` including the ADR 0027 rule, fixed `k`, `n = 1`, `n = 2` on both sides of the threshold, rescue cases).
- The re-tuned threshold is recorded in a new ADR with the clips and measurements it was derived from, including the zero-padding comparison (and the 16-bit comparison if it was done).
- On the LibriSpeech fixture clips (tagged `Integration`), a recording of speakers 1089 → 1188 → 1089 is labelled with two speakers and turn boundaries at the speaker changes.
- The speakers of a diarized task are stored for every segment, all or nothing.
- Clustering time and memory at 1000 and 3000 segments are measured and recorded in the Approval log.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: consultation decisions approved: the automatic speaker count yields one speaker whenever the last merge is below the threshold, not only for two segments ([ADR 0027](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md); the user: the oracle is buggy in this regard); threshold tuning on about 6 LibriSpeech speakers × 3 utterances, whole clips and excerpts, midpoint rule, throwaway measurement, value in a new ADR; the 16-bit measurement only if quick; the zero-padding comparison is measured and recorded with the threshold (agent proposal, part of the same measurement); the speaker limit moves to items 12 and 14; clustering is ported as the oracle has it and revisited if performance becomes an issue; speaker turns keep each segment's start, end and text (groundwork for a possible playback view). Added by the agent and approved with the spec: the 500 ms boundary in whole milliseconds, the `stt/db` speaker persistence, the pure labelling function, pause and cancellation between merge iterations, and "`speakers = off` leaves `segments.speaker` NULL" moved to item 11. Test list of 19 items approved (two seed items dropped: clusters without long members are unreachable through the orchestration; pausing between embedding windows is covered by item 08).
- 2026-10-09: cycle order approved: clustering items (1–10), short-segment handling and rescue (11–13), persistence (14), turns (15), summary (16), pause/cancel (17), then fixtures, threshold measurement and ADR, then the Integration items (19 before 18).
- 2026-10-09 (cycle 1, zero or one segment): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: compilation failed, "Unresolved reference 'clusterSegments'". Green: `clusterSegments(embeddings, k, maxSpeakers)` in `stt.diarization` returns all zeros for `n <= 1`; `gradle check` (106 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09 (cycle 2, two segments in auto mode): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: first compilation failed ("Unresolved reference 'SPEAKER_DISTANCE_THRESHOLD'"); with the constant added (0.35, oracle placeholder), "kotlin.NotImplementedError: An operation is not implemented: clustering of two or more segments". Green: two segments in auto mode get one speaker when their cosine distance (in double) is below `SPEAKER_DISTANCE_THRESHOLD`, else two; the test uses the two adjacent float32 inputs around the threshold, derived from the constant. Exactly-at is reachable only when `1 − threshold` is a float32 value (not for 0.35), so until then the test does not tell `<` from `<=`. `gradle check` (107 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09 (cycle 3, UPGMA merge heights): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: compilation failed, "Unresolved reference 'upgmaMerges'". Green: `upgmaMerges(embeddings): List<Merge>` (flat `DoubleArray` distance matrix, first row-major minimum over the active clusters, size-weighted row update). Fixture: unit vectors at 0°, 20°, 50°, 120°; expected heights are the all-member-pairs mean distances from trigonometry, merges (0,1), (0,2), (0,3). Mutation check (not committed): an unweighted WPGMA update fails the test with "0.997401985861412 should be equal to 1.1105426781137537 within tolerance of 1.0E-6". `gradle check` (108 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 3: `clusterSegments` takes the two-segment height from `upgmaMerges(...).last()` instead of its own distance call.
- 2026-10-09 (cycle 4, tied distances): green on arrival (cycle 3's search keeps the first strict minimum); red recorded against a temporary, uncommitted mutation (`<=`, which keeps the last minimum): `gradle test --tests 'stt.diarization.ClusteringSpec'`, the disjoint-pairs case fails with "expected:<[(0, 1), (2, 3), (0, 2)]> but was:<[(2, 3), (0, 1), (0, 2)]>", and with that assertion skipped the same-row case fails with "expected:<[(0, 1), (0, 2)]> but was:<[(0, 2), (0, 1)]>". Ties are exact in float32 (each tied dot product is the single product 1 · 0.8). No production change; `gradle check` (109 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 4: `List<Merge>.pairs()` test helper in `ClusteringSpec` replaces three `map { it.i to it.j }`.
- 2026-10-09 (cycle 5, three groups in auto mode): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: "kotlin.NotImplementedError: An operation is not implemented: three or more segments and fixed counts". Green: auto mode with `n >= 3` takes `k = n - (idx + 1)` at the first largest gap over all merge heights, then cuts the first `n - k` merges with union-find and numbers the roots by first appearance; the `max_speakers` window and clamp are left to items 6 and 7, the ADR 0027 rule for `n >= 3` to item 8. Fixture: three groups of two on orthogonal planes (cross distances exactly 1; within 0.2, 0.4, 0.04), expected `k` = 3 derived by hand from the heights. `gradle check` (110 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09 (cycle 6, gap window): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: "expected:<[0, 0, 0, 1, 1, 1]> but was:<[0, 0, 1, 2, 3, 4]>" (all gaps searched, k = 5). Green: `largestGapCount` searches from `start = max(0, gaps - maxSpeakers)`. Fixture: two groups of three in orthogonal subspaces, each a pair at dot product c plus a point at dot product t from both, so the heights are exactly 0.015, 0.55, 0.6, 0.65, 1; with `maxSpeakers = 3` the window rule gives k = 2, while the item 7 clamp alone would give 3, so the two rules are told apart. `gradle check` (111 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 6: `largestGapCount` names the window start `start`, separate from the result index `idx`.
- 2026-10-09 (cycle 7, clamp to max_speakers): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: "expected:<[0, 0, 0]> but was:<[0, 0, 1]>". Green: the auto count is clamped to `[1, maxSpeakers]`. Fixture: 3 and 4 orthogonal unit vectors (every height 1, every gap 0, so the first gap of the window wins and the unclamped count is `maxSpeakers + 1`): `maxSpeakers = 1` → `[0, 0, 0]`, `maxSpeakers = 2` → `[0, 0, 0, 1]` (labels derived from the item 4 tie rule). `gradle check` (112 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 7: the `largestGapCount` KDoc states when the clamp applies (the first gap of a full window being the largest would leave `maxSpeakers + 1` clusters).
- 2026-10-09 (cycle 8, one speaker below the threshold for n >= 3): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: "expected:<[0, 0, 0]> but was:<[0, 0, 1]>". Green: in auto mode a last merge height below `SPEAKER_DISTANCE_THRESHOLD` yields one speaker for every `n >= 2` (ADR 0027); otherwise `n == 2` gives two and `n >= 3` the largest-gap count. Fixture: unit vectors at 0°, 10°, 30°; merges {0} + {1} at d(10°) ≈ 0.015 and {0, 1} + {2} at (d(30°) + d(20°)) / 2 ≈ 0.097, guarded below the threshold in the test, so it stays valid for any tuned threshold above that height; the gap rule alone gives k = 2. `gradle check` (113 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 8: the angle helpers defined in two tests become file-private `unitAt(degrees)` and `distanceAt(degrees)` in `ClusteringSpec`.
- 2026-10-09 (cycle 9, fixed k): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: "kotlin.NotImplementedError: An operation is not implemented: fixed counts: next test-list items". Green: a non-null `k` is clamped to `[1, n]` and cut like the auto count; `maxSpeakers` is not consulted. Fixture: cycle 5's three groups of two on orthogonal planes, merges (4, 5) 0.04, (0, 1) 0.2, (2, 3) 0.4, (0, 2) 1, (0, 4) 1 derived by hand (ties to the lower pair); with `maxSpeakers = 1`: k = 2 → `[0, 0, 0, 0, 1, 1]`, k = 4 → `[0, 0, 1, 2, 3, 3]`, k = 1 and k = 0 → all 0, k = 10 → `[0, 1, 2, 3, 4, 5]`. `gradle check` (114 unit tests) and `gradle integrationTest` (44 run) green.
- 2026-10-09: refactor approved and done after cycle 9: the six-row fixture copied in the cycle 5 and cycle 9 tests becomes the file-private `threeOrthogonalPairs()` in `ClusteringSpec`.
- 2026-10-09: refactor approved and done after cycle 9: `clusterSegments` drops the `speakers == 1` early return; `cutLabels` with all `n - 1` merges yields all 0. The user asked that this stays covered: temporary, uncommitted mutations of the one-speaker path, before the removal (`List(n) { it }`) and after it (one merge fewer when `speakers == 1`), each fail four existing tests (cycles 2, 7, 8 and 9), e.g. "expected:<[0, 0, 0]> but was:<[0, 0, 1]>"; `gradle check` green after the removal.
