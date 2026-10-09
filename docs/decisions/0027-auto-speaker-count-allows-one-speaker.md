# 0027 — Automatic speaker count allows one speaker

Status: accepted · Date: 2026-10-09

## Context
[ADR 0003](0003-kotlin-diarization-campplus-onnx.md) ports the oracle's clustering 1:1, including how `cluster_segments` picks the speaker count in auto mode: with exactly two clustered segments, one speaker when their distance is below a threshold (0.35 for ECAPA, re-tuned for CAM++); with three or more, the largest gap among the last `max_speakers` merge heights. That gap rule can only yield `k = n − (idx + 1)` with `idx <= n − 3`, i.e. `k >= 2`. So any recording with three or more segments of at least 0.5 s is split into at least two speakers, even a single speaker (a lecture, a voice memo). The user judged the oracle buggy in this regard (roadmap item 09 consultation, 2026-10-09).

## Decision
- Auto mode: when the last merge height of the UPGMA dendrogram (the average distance between the final two clusters, the highest merge) is below the threshold, `k = 1`.
- Otherwise the oracle's rules apply unchanged: `n == 2` → `k = 2`; `n >= 3` → the largest-gap rule, clamped to `[1, max_speakers]`.
- For `n == 2` the new rule is exactly the oracle's; it now applies to every `n >= 2`.
- The threshold is the one ADR 0003 re-tunes for CAM++; its value and the measurements behind it are recorded in a further ADR written in roadmap item 09.

## Consequences
- A single speaker is labelled as one speaker in auto mode, whatever the number of segments.
- The threshold now affects every auto-mode task, not only two-segment ones, so it is tuned on more fixture data (item 09: about 6 LibriSpeech speakers × 3 utterances, whole clips and excerpts).
- Amends the "ported 1:1" speaker-count rule of ADR 0003 and the clustering description in [ADR 0002](0002-no-python-oracle-only.md); everything else in `cluster_segments` (UPGMA, the gap rule, fixed-`k` clamping, first-appearance labels) stays a 1:1 port.
- Fixed-`k` mode and `off` are unchanged.
