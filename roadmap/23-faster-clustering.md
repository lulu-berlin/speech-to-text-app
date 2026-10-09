# 23 — Faster speaker clustering

Status: in-progress
Depends on: [09](../docs/specs/diarization.md) (done)

## Goal

Make speaker clustering fast and light enough for long recordings without changing who is labelled as which speaker. Before switching, measure the difference from the current port of the oracle and record it.

## Already decided

- [Item 09 spec](../docs/specs/diarization.md): UPGMA (average linkage) on cosine distance, ported 1:1 from the oracle (`O(n²)` memory for the distance matrix, `O(n³)` time). Measured: 1000 segments 0.68 s / 8 MiB, 3000 segments 11.4 s / 69 MiB. The spec says to revisit this, with its own decision, if performance becomes an issue.
- User decision (item 10/11 consultation, 2026-10-09): optimize now, as its own item in parallel with item 11, and measure the accuracy impact.
- [ADR 0027](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md), [ADR 0032](../docs/decisions/0032-speaker-distance-threshold-for-campplus.md): the speaker-count rules and the threshold apply to the merge heights. They are unchanged by this item.
- The distance stays `1 − a·b` computed in double on L2-normalized embeddings (item 09), whatever computes it.

## Proposal (approved 2026-10-09)

- **Algorithm: nearest-neighbor chain** for average linkage (decided 2026-10-09):
  - finds the same merges at the same heights as the current search, because average linkage is reducible;
  - **no stored distance matrix: distances are computed on demand**, so memory is `O(n)` instead of `O(n²)`. This trades memory for repeated 512-dim dot products, so it may grow the time constant; the measurement below weighs it (see the risk note).
  - **Merge order**: average linkage's merge heights are non-decreasing, so ordering the new merges by height reproduces the current sequence. Where two different merges have exactly equal heights the pair may differ — accepted and documented (decided 2026-10-09), and the gap rule (and so `k`) can differ in that case.
  - `upgmaMerges`' contract is unchanged: merges in merge order, the pause gate and cancellation checked between merges, progress reported per merge (item 11's `CLUSTERING` stage).
- **Measurement before switching** (throwaway program; numbers and the decision go into a new ADR):
  - label agreement on item 09's 18 LibriSpeech clips (whole and excerpts) and on random sets at **1000 / 3000 / 5000 segments**, both auto and fixed `k`;
  - time and memory for **three** implementations: the current one, nearest-neighbor chain with a stored matrix, and nearest-neighbor chain with on-demand distances (so the cost of `O(n)` memory is visible);
  - the reference values come from the test-sources implementation (below).
- **Switch rule** (decided 2026-10-09): switch only if the labels are **identical apart from exact height ties**; any other difference stops the item for a decision with the user. The measured agreement is recorded either way.
- **The current `O(n³)` implementation moves into the test sources** (decided 2026-10-09) as the reference for a differential test over random matrices; item 09's hand-derived fixtures and the fixture-clip Integration tests stay. Production keeps one implementation.
- **Risk to settle in the measurement**: dropping the matrix may make the algorithm slower than the current one at 3000 segments, because every nearest-neighbor scan recomputes distances. If the `O(n)` variant does not beat the current implementation at 5000 segments, stop and put the numbers to the user before choosing.
- Item 11 is not blocked: it uses item 09's clustering until this item switches the implementation behind the same function.

## Open questions (answered 2026-10-09)

- Switch rule? → **Identical labels apart from exact ties**, with the measured agreement recorded.
- The current implementation? → **Keep it in the test sources** as the reference for a differential test; production keeps one.
- Tie order? → **A different tie order is accepted and documented**; the test item is relaxed accordingly.
- Measurement size? → **1000 / 3000 / 5000 segments.**
- Memory? → **Also cut it to `O(n)`** (no stored matrix), measuring the three variants so the time cost is visible.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The new merges equal item 09's hand-computed merge heights for the 4-point matrix
- [ ] On inputs without equal distances, the labels equal the current clustering's for auto and fixed `k`
- [x] The differential test over random matrices agrees with the test-sources reference, except where two heights are exactly equal
- [ ] Ties are resolved reproducibly for a given input (the exact pair is not required to match the old implementation)
- [ ] Clustering still pauses and cancels between merges and reports progress per merge
- [ ] Clustering allocates no distance matrix: peak memory stays well under the `O(n²)` matrix size at 3000 segments
- [ ] All item 09 tests stay green, Integration included (1089 → 1188 → 1089, threshold pairs)

## Acceptance criteria

- The measurement (agreement, time, memory for the three variants) is recorded in a new ADR together with the decision.
- If switched: at 5000 segments the new implementation is faster than the current one and uses `O(n)` memory, and labels are unchanged apart from documented exact-tie cases.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item created at the user's request (item 10/11 consultation): optimize clustering now, in parallel with item 11, and measure the accuracy impact.
- 2026-10-09: consultation answers approved: switch only on identical labels apart from exact height ties (agreement recorded); the current `O(n³)` implementation moves into the test sources as a differential reference; a different tie order is accepted and documented; the measurement covers 1000/3000/5000 segments and all three variants; memory is cut to `O(n)` by computing distances on demand, with the time cost measured. Test list not yet approved.
- 2026-10-09: test list (7 items) approved by the user; status `spec-approved`.
- 2026-10-09 (cycle 1, test-list item 3, differential harness): red — `gradle test --tests 'stt.diarization.ClusteringSpec'`: compilation failed, "Unresolved reference 'naiveUpgmaMerges'". Green: the item 09 `O(n³)` implementation moved into test sources as `NaiveUpgma.kt` (`naiveUpgmaMerges`/`naiveClusterSegments`, renamed only,including copies of `largestGapCount`/`cutLabels`), and a new `ClusteringSpec` block compares `upgmaMerges` with `naiveUpgmaMerges` on five random L2-normalized 512-d matrices (n = 2, 3, 5, 8, 17, fixed seeds): per-index heights within 1e-12 and pairs equal, except a pair may differ where its height ties with another (1e-9); a hand-built tied matrix (four orthogonal pairs at 0.2, cross distances 1) exercises the accepted exception. `gradle check` (132 unit tests) green.
