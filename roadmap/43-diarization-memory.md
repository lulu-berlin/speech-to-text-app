# 43 — Diarization memory: stream speaker windows from the stored clip

Status: spec-approved
Depends on: [11](../docs/specs/processing-engine.md) (done; `clipPcm` lives in the engine)
Blocks: —

## Goal

Diarizing a long recording must not reconstruct the full PCM clip. It streams speaker windows from the stored canonical clip while preserving global timestamps and producing the same speakers and segments as before. Peak incremental JVM heap during diarization stays within one whole-clip PCM FloatArray size above the post-model-load baseline; measure it on a long recording.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Current evidence (after item 44): `ProcessingEngine.clipPcm()` materializes the whole canonical clip, decodes the whole PCM to a file, reads that file into a full-size `byte[]`, then copies it into a full-size `FloatArray`. `Embeddings.embed()` holds that array and creates per-segment windows from it. At 16 kHz mono float, one PCM representation is ~115 MB per 30 min (~461 MB for 2 h), before the ONNX session; simultaneous full-size representations multiply that peak.
- The user decided to include direct speaker-window streaming from the stored clip in this item rather than leave it as a follow-on. Windowing must preserve global timestamps so embedding windows and clustering output remain identical.

## Proposal

Stream the speaker windows from the stored canonical clip into diarization instead of materializing the full clip as a PCM `FloatArray`. Preserve global sample/timestamp positions across windows so speaker embeddings and clustering remain behaviorally unchanged. Measure peak incremental JVM heap above the post-model-load baseline on a long clip.

## Decisions (consultation, 2026-10-15)

- Stream speaker windows from the stored canonical clip in item 43; do not split this into a later item.
- For the long-clip memory check, peak incremental JVM heap above the post-model-load baseline is bounded by one whole-clip FloatArray (`sampleCount × 4` bytes).
## Test list (approved 2026-10-15; behavioral scenarios)

- [ ] Diarizing a multi-window clip produces the expected speaker assignments, segments and timestamps, including speaker windows that cross chunk/window boundaries.
- [ ] A recording of at least 20 minutes diarizes completely with the expected output; peak incremental JVM heap above the post-model-load baseline is no more than one whole-clip FloatArray (`sampleCount × 4` bytes), and the clip length and measured peak are recorded on the user's machine.
- [ ] (must not break) `ProcessingEngineDiarizationSpec` and the clustering suites stay green.

## Acceptance criteria

- Diarization streams speaker windows from the stored canonical clip rather than reconstructing whole-clip PCM; expected speakers, segments and global timestamps are unchanged.
- On a recording of at least 20 minutes, the measured peak incremental JVM heap above the post-model-load baseline is no more than one whole-clip FloatArray (`sampleCount × 4` bytes).
- All test-list items ticked and green; user approved the diff.


## Approval log

- 2026-10-12: filed from the third-party review's finding 4 after code triage (multi-copy rebuild path; the reviewer's math confirmed).
- 2026-10-15: consultation — the user chose to stream speaker windows from the stored canonical clip in this item, not defer it. Test list approved: output parity across window boundaries; a 20-minute-or-longer clip with an incremental JVM heap limit of one whole-clip FloatArray above the post-model-load baseline, measured on the user's machine; existing diarization and clustering suites stay green. The structural allocation check was removed from the test list because it is not a behavioral scenario. Status: spec-approved.