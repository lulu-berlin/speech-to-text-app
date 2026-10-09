# 23 — Faster speaker clustering

Status: draft
Depends on: [09](../docs/specs/diarization.md) (done)

## Goal

Make speaker clustering fast enough for long recordings without changing who is labelled as which speaker. Before switching, measure how often the faster algorithm's labels differ from the current port of the oracle.

## Already decided

- [Item 09 spec](../docs/specs/diarization.md): UPGMA (average linkage) on cosine distance, ported 1:1 from the oracle (`O(n²)` memory, `O(n³)` time). Measured: 1000 segments 0.68 s / 8 MiB, 3000 segments 11.4 s / 69 MiB. The spec says to revisit this, with its own decision, if performance becomes an issue.
- User decision (item 10/11 consultation, 2026-10-09): optimize now, as its own item in parallel with item 11, and measure the effect on accuracy.
- [ADR 0027](../docs/decisions/0027-auto-speaker-count-allows-one-speaker.md), [ADR 0032](../docs/decisions/0032-speaker-distance-threshold-for-campplus.md): the speaker-count rules and the threshold apply to the merge heights. They are unchanged by this item.

## Proposal (pending approval)

- **Algorithm: nearest-neighbor chain** for average linkage:
  - `O(n²)` time and `O(n²)` memory, instead of `O(n³)` time.
  - Average linkage is "reducible", so the nearest-neighbor chain finds the same merges at the same heights as the current search. Only the order of merges can differ.
  - The oracle's speaker-count rule reads the heights in merge order. After sorting the merges by height (ties: lower index pair first, as now), the sequence is the current one, except when distinct merges have exactly equal heights.
- **Measurement before switching** (throwaway program, numbers kept in a new ADR):
  - label agreement between the current clustering and the new one on item 09's 18 LibriSpeech clips (whole and excerpts) and on random sets of 1000/3000/5000 segments, auto and fixed `k`;
  - time and memory at 1000, 3000 and 5000 segments for both.
- **Switch rule:** replace item 09's merge search only if the labels agree everywhere except where exact height ties occur. If they differ otherwise, stop and decide with the user.
- `upgmaMerges`' contract stays the same: merges in merge order, the pause gate and cancellation checked between merges, and progress reported per merge (item 11's `CLUSTERING` stage).

## Open questions for the user

- Is the switch rule above the right acceptance (identical labels apart from exact ties), or is a measured agreement percentage enough?
- Should the current algorithm stay in the code as a test reference (comparing both on random inputs), or be removed after the switch?
- Is 5000 segments (about 5 h of audio) the right upper size for the measurement?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] The new merges equal item 09's hand-computed merge heights for the 4-point matrix
- [ ] On inputs without equal distances, the labels equal the current clustering's for auto and fixed `k`
- [ ] Ties are resolved as before: the lower-index pair merges first
- [ ] Clustering still pauses and cancels between merges and reports progress per merge
- [ ] All item 09 tests stay green, Integration included (1089 → 1188 → 1089, threshold pairs)

## Acceptance criteria

- The measurement (agreement, time, memory) is recorded in a new ADR together with the decision to switch or not.
- If switched: 3000 segments cluster in well under the current 11.4 s, and labels are unchanged apart from documented exact-tie cases.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item created at the user's request (item 10/11 consultation): optimize clustering now, in parallel with item 11, and measure the accuracy impact.
