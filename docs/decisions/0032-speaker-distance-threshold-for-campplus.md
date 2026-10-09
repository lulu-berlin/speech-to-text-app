# 0032 — Speaker-distance threshold 0.5448 for CAM++

Status: accepted · Date: 2026-10-09

## Context
The oracle's 0.35 cosine-distance threshold was tuned for ECAPA embeddings; [ADR 0003](0003-kotlin-diarization-campplus-onnx.md) requires it to be re-tuned for the CAM++ embeddings of item 08, and [ADR 0027](0027-auto-speaker-count-allows-one-speaker.md) applies it to every automatic speaker count (one speaker when the last merge is below it). Roadmap item 09 fixed the procedure: about 6 LibriSpeech speakers × 3 utterances, whole clips and excerpts of typical segment lengths, the midpoint between the highest same-speaker and the lowest different-speaker distance, or the value with the fewest misclassified pairs if the ranges overlap; a throwaway measurement, only its numbers kept. Item 08 deferred two questions to the same measurement: the 2 s zero-padding of short windows and the effect of 16-bit quantization.

## Measurement
- Clips (committed, ADR 0026; `backend/src/test/resources/librispeech/`, unmodified LibriSpeech test-clean, 16-bit FLAC, so decoding them equals the app's rebuild from 16-bit FLAC chunks, ADR 0024): 6 readers, 3 utterances each, 5.1–12.0 s — 1089 (M) 134686-0000, 134686-0002, 134691-0023; 1188 (M) 133604-0000, 133604-0001, 133604-0025; 260 (M) 123286-0000, 123288-0001, 123440-0010; 121 (F) 121726-0000, 123859-0003, 127105-0000; 237 (F) 126133-0001, 134493-0001, 134500-0000; 1221 (F) 135766-0004, 135766-0006, 135767-0005.
- Embedder: the app's (`Embeddings.embed`, CAM++ of ADR 0003, 2 s minimum window); distance `1 − a·b` of the L2-normalized embeddings, as clustering computes it. All pairs within each set:

  | Set | same-speaker pairs: min / mean / max | different-speaker pairs: min / mean / max | separated |
  | --- | --- | --- | --- |
  | whole clips | 18: 0.0810 / 0.2098 / 0.4091 | 135: 0.5814 / 0.8148 / 0.9832 | yes |
  | 1.5 s excerpts (from 0.5 s) | 18: 0.2763 / 0.4423 / 0.7007 | 135: 0.5142 / 0.8754 / 1.1186 | no |
  | 3 s excerpts | 18: 0.1613 / 0.3460 / 0.5611 | 135: 0.5448 / 0.8567 / 1.0805 | no |
  | 5 s excerpts | 16: 0.0987 / 0.2386 / 0.4662 | 120: 0.5520 / 0.8362 / 0.9957 | yes |
  | 8 s excerpts (the 7 clips of at least 8.5 s) | 2: 0.1012 / 0.1614 / 0.2216 | 19: 0.7049 / 0.8259 / 0.9591 | yes |

- All 616 pairs together overlap (same-speaker max 0.7007, different-speaker min 0.5142). Misclassified pairs (same-speaker at or above t, different-speaker below t): t = 0.45: 13; 0.50: 10; 0.52: 9; 0.54: 6; **0.5448: 5** (4 same, 1 different), the minimum, reached only for t in (0.5447, 0.5448]; 0.55: 7; 0.56: 9; 0.58: 10; 0.60: 12. Whole clips have no error anywhere in 0.41–0.58.

## Decision
- `SPEAKER_DISTANCE_THRESHOLD` = **0.5448**: the value with the fewest misclassified pairs, as the procedure prescribes for overlapping ranges (the user chose it over 0.54, which is one error worse but not on an observed distance). At this value the whole-clip pairs and the 5 s-excerpt pairs are all classified correctly; a permanent `Integration` test checks exactly those 289 pairs.
- `1 − 0.5448` is not a float32 value, so the unit test of the two-segment rule still cannot tell `<` from `<=` (item 09, cycle 2).
- **Zero padding stays at 2 s** (item 08's port of the oracle). Short excerpts at 2.0 s into each clip against the other whole clips (nearest whole clip is the own speaker; own-minus-other mean cosine, mean / worst):

  | Minimum window | 0.6 s excerpts | 1.0 s excerpts |
  | --- | --- | --- |
  | 2 s (current) | 16/18; 0.291 / −0.008 | 17/18; 0.394 / 0.073 |
  | 1 s | 17/18; 0.337 / 0.037 | 18/18; 0.405 / 0.041 |
  | none | 16/18; 0.323 / 0.018 | 18/18; 0.405 / 0.041 |

  The 1 s window helps sub-second segments by one clip of 18 but makes the clustered 1.5 s segments worse: over all pairs the fewest errors rise from 5 to 9 (10 at 0.5448), the 1.5 s same-speaker maximum from 0.7007 to 0.7061. Sub-second segments are rescued, not clustered, so the numbers do not clearly favor a change.
- **16-bit quantization has no relevant effect**: 1089-134686-0002 coded as Opus 32 kbit/s, embedded from its float32 decode and from its 16-bit FLAC rebuild (ADR 0024): cosine distance 0.000043.

## Consequences
- More segments of one speaker stay together than with 0.35 (whole-clip same-speaker distances reach 0.41, which 0.35 would have split). Short segments of different speakers can still merge: 1 of the 544 measured different-speaker pairs (two 1.5 s excerpts, 0.5142) lies below 0.5448, and a 3 s pair lies exactly on it (0.5448, classified as different because the rule is `<`); 4 same-speaker excerpt pairs lie at or above it.
- The threshold is measured on read speech of 6 readers; conversational audio with overlapping speech may need a re-measurement, recorded as a new ADR.
- Item 08's deferred questions are closed: 2 s padding kept, 16-bit rebuild confirmed harmless.
