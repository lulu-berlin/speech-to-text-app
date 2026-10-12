# 43 — Diarization peak memory: drop the intermediate PCM copies

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done; `rebuildPcm` lives in the engine)
Blocks: —

## Goal

Diarizing a long recording keeps its peak memory near one in-memory PCM representation, not several. The first step removes the full-size intermediate byte-array copies; streaming speaker windows directly from the decoded chunks is the follow-on.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Evidence (`ProcessingEngine.rebuildPcm()` → `Embeddings.embed()`): the whole recording is rebuilt into a `ByteArrayOutputStream`, copied into a byte array, then converted into a `FloatArray`; the embed step then reads windows from that full array. At 16 kHz mono float, one PCM representation is ~115 MB per 30 min (~461 MB for 2 h), and several copies can be alive at the peak, before the ONNX session.
- The long-term design (process speaker windows directly from stored chunks, preserving global timestamps so embedding windows and clustering output stay identical) is a real project and keeps its own item; this item is the cheap first step and the measurement.

## Proposal

To be settled in the consultation. Starting point: eliminate the intermediate full-size `byte[]` (decode the PCM straight into the target `FloatArray`), so the peak is one representation; measure the effect on a long clip (e.g. the user's machine, or the sandbox, with a generated long recording) and record the numbers in the log.

## Open questions (to be answered in the consultation)

- Is the chunk-window streaming (no full reconstruction at all) a follow-on item now, or recorded for later?

## Test list (proposed; behaviors only)

- [ ] Diarizing a clip produces identical speakers/segments/timings with the new path (no behavioral drift, oracle-consistent)
- [ ] The decode path writes into the output array directly (no full-size intermediate byte array is allocated — a structural check)
- [ ] A long recording (at least 20 min of PCM) diarizes within a recorded peak-memory budget, and the numbers are logged (user-verified on the user's machine)
- [ ] (must not break) The diarization suites (`ProcessingEngineDiarizationSpec`, clustering) stay green

## Acceptance criteria

- One in-memory PCM representation at the peak, no full-size intermediate copy; diarization output unchanged; the measurement recorded.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 4 after code triage (multi-copy rebuild path; the reviewer's math confirmed).