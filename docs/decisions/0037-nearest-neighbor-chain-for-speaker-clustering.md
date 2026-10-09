# 0036 — Nearest-neighbor chain for speaker clustering

Status: accepted · Date: 2026-10-09

## Context
[Roadmap item 23](../README.md) (in progress; spec `roadmap/23-faster-clustering.md`) replaces the item 09 UPGMA port's
`O(n²)` distance matrix and `O(n³)` minimum search with the **nearest-neighbor chain** for average linkage, computed on
demand (no stored matrix, `O(n)` memory), after measuring the alternative. The consensus at the item's creation
([ADR 0027](0027-auto-speaker-count-allows-one-speaker.md), [ADR 0032](0032-speaker-distance-threshold-for-campplus.md)
unchanged) was: switch only if the labels are identical apart from exact height ties, and if the on-demand variant beats
the current implementation at 5000 segments;the current `O(n³)` implementation moves to the test sources as the
differential reference.

The merge heights under test are the ones the speaker-count rules read: the ADR 0027 rule (last merge below 0.5448 →
1 speaker) and the largest-gap rule of item 09, so a different merge sequence with the same heights would already be a
visible behaviour change;the 5000-segment acceptance requirement exists because dropping the matrix trades memory for
recomputed 512-dim dot products.

## Measurement (throwaway program, deleted afterwards;same method as the item 09 measurement: one warm-up run, then one
measured run, heap-pool resets, agent sandbox, 8 CPUs, default `gradle test` JVM with a 512 MiB max heap;final memory
column measured with a sampler thread over the live heap after a GC baseline)

Inputs: random L2-normalized 512-d matrices at 1000 / 3000 / 5000 segments (fixed seeds), plus the 18 committed
LibriSpeech fixture clips (whole and 5 s excerpts) embedded with the app's model. Label agreement was recorded for
auto and fixed `k` (5 for the random sets, 2 for the clips).

| n | current (`O(n³)`, matrix) | chain, stored matrix | chain, on-demand (`O(n)`) |
| --- | --- | --- | --- |
| time, 1000 | 0.70 s | 0.28 s | 1.20 s |
| time, 3000 | 11.98 s | 2.33 s | 11.95 s |
| time, 5000 | 50.54 s | 8.19 s | 36.07 s |
| heap, 1000 | 8 MiB | 12 MiB | 4 MiB |
| heap, 3000 | 71 MiB | 81 MiB | 14 MiB |
| heap, 5000 | 198 MiB | 212 MiB | 25 MiB |

(Memory is the live-heap increase of the measured run;the dense matrix is 7.6 / 68.7 / 190.7 MiB at these sizes, so
the current and chain-with-matrix figures are that matrix plus temporaries;the on-demand figure is the per-cluster
mean vectors, `n·512·8` bytes = 4.1 / 12.3 / 20.5 MiB.)

Label agreement: every random set and every fixture clip agreed with the current implementation at every `k` — zero
mismatches, and no exact height ties occurred anywhere (`ties = false` throughout). The chain-with-matrix and the
chain-on-demand merge sequences differ from each other only in heights within one ulp, never in the pair.

## Decision
- `upgmaMerges` becomes a **nearest-neighbor chain with on-demand distances** (average linkage; average linkage is
  reducible, so the chain finds the same merges). No distance matrix;each cluster keeps the mean of its members'
  embeddings, and the distance between clusters A and B is computed on demand in double as `1 − vA·vB` (the arithmetic
  identity of the mean of the member-pair distances, by bilinearity). Distances for initial singleton pairs equal the
  item 09 values bit for bit;merged clusters round differently at worst by one ulp.
- Merges are **sorted by height** (stable) after the chain, so the sequence matches the current merge order. Where two
  different merges have exactly equal heights the pair at each position may differ — accepted and documented
  (user decision 2026-10-09);the gap rule, and so `k`, can differ in exactly that case.
- The merge contract is unchanged: `(i, j)` with `i < j`, cancellation and the pause gate checked between merges,
  progress reported per merge (item 11's `CLUSTERING` source).
- The switch happened because the rule's conditions are met: labels identical apart from exact ties (zero mismatches
  observed), and at 5000 segments the on-demand variant is faster than the current implementation (36.07 s vs
  50.54 s). At 1000 it is slower (1.20 s vs 0.70 s) and at 3000 about equal (11.95 s vs 11.98 s), the expected cost
  of recomputing dot products, outweighed from 3000 segments up. The chain with a stored matrix is faster at every
  size (8.19 s at 5000) but keeps the `O(n²)` memory, which was the reason for the item.
- The `O(n³)` implementation is kept **only in test sources** as `backend/src/test/kotlin/stt/diarization/NaiveUpgma.kt`
  (differential reference);production has exactly one clustering implementation.

## Consequences
- Memory of clustering drops from `O(n²)` to `O(n)` (per-cluster mean vectors, `n·512` doubles);at 5000 segments the
  measured clustering holds ~25 MiB instead of ~198 MiB.
- Time stays `O(n²)` in distance computations (`O(n³)` dot operations: each of the `O(n²)` scans needs one 512-dim
  dot;the chain is measured 50.5 → 36.1 s at 5000, with the stored-matrix chain at 8.2 s as the upper bound on the
  time the matrix buys).
- The first-merge heights of a tied input can differ from the item 09 order within an exact-tie run;the differential
  test (item 23) allows exactly that and the tie-reproducibility test pins that the choice is deterministic.
- ADR 0027 and ADR 0032 are untouched: they read the merge heights, and the heights are the same except for ulp-level
  rounding and tie-run internal order.
- Item 09's hand-derived fixtures, the fixture-clip Integration tests and the threshold pairs must all stay green
  (test list item "All item 09 tests stay green, Integration included").
- The item 09 acceptance number is superseded: clustering at 1000/3000 is now ~0.7/12 s with ~8/71 MiB instead of
  0.68 s/8 MiB and 11.4 s/69 MiB (the same within noise at 1000 and 3000;the win and the memory cut show at the
  5000-segment scale).