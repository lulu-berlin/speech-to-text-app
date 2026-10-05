# 0002 — No Python in the app; transcribe.py is a reference oracle only

Status: accepted · Date: 2026-10-05

## Context
An existing Python CLI, [`transcribe.py`](../oracle/transcribe.py), already transcribes audio with whisper-cli and adds speaker diarization (speechbrain ECAPA-TDNN embeddings plus agglomerative clustering). Its behavior is the target for the app, but embedding a Python runtime (PyTorch, speechbrain, numpy) would add a second language, a large dependency closure, and a second packaging story to a Kotlin/TypeScript app (ADR 0001).

## Decision
- The app, its build, and its tests contain no Python.
- `docs/oracle/transcribe.py` is a read-only reference oracle: agents read it to port behavior; it is never executed by the app, the build, or the tests. It lives under `docs/oracle/` to keep the repository root uncluttered.
- Oracle behaviors to port to Kotlin:
  - Output formats: `write_diarized_outputs` (txt, srt, vtt, json) with `fmt_srt` / `fmt_vtt` timestamp formatting.
  - Segment parsing: `parse_segments` (whisper-cli JSON, both the `segments` shape in seconds and the `transcription` shape with `offsets` in milliseconds).
  - Embedding windowing: `embed_windows` — one window per segment; windows shorter than `MIN_EMBED_SECONDS` (2 s) are zero-padded with the audio centered; windows longer than `MAX_EMBED_SECONDS` (20 s) use a centered 20 s slice; segments at or past the end of the audio use the tail window. Segments shorter than `SHORT_SEGMENT_SECONDS` (0.5 s) are excluded from clustering (`diarize_and_write`) but still embedded for rescue.
  - Clustering: `cluster_segments` (UPGMA on cosine distance, speaker count from the largest gap among the last `max_speakers` merges, fixed-k clamp, first-appearance relabel).
  - Short-segment rescue: `_rescue_short_segments` (nearest cluster centroid, including non-neighbor speakers).
  - Turn merging: `speaker_turns` (consecutive segments of the same speaker collapse into one turn).

## Consequences
- Each ported behavior is specified in its roadmap item, and test expected values may be derived by hand from the oracle's logic — never by running it.
- The oracle's speechbrain ECAPA model is replaced (see ADR 0003); behaviors that depend on embedding values (the n == 2 threshold) must be re-tuned rather than copied.
- Oracle details that are PyTorch-specific workarounds (e.g. duration-sorted batching in `embed_windows`) are not behavior and are not ported.
- `docs/oracle/transcribe.py` is not edited; it stays as the frozen reference.
- The rule is restated as non-negotiable in [AGENTS.md](../../AGENTS.md).
