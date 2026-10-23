# 73 — Diarization speaker separation: unseparated speakers, and a split remedy

Status: draft
Depends on: —
Blocks: —

## Goal

The automatic speaker count separates real recordings better than it does today — distinct
speakers less often collapse under one id — and when a speaker still gets split, a **split** action
gives the user the remedy that unseparation lacks today (merge exists, split does not).

## Evidence

- **User report 2026-10-14**: diarization sometimes puts distinct speakers under one id and
  sometimes splits one speaker across ids. A split has the merge remedy; an unseparated pair has no
  split tool, and re-running reproduces the same clustering for the same input.
- The auto count is two hard-coded heuristics: one speaker when the last merge's cosine distance is
  below `SPEAKER_DISTANCE_THRESHOLD = 0.5448` (`diarization/Clustering.kt:8-11`), otherwise the
  largest-gap cut among the last `maxSpeakers` gaps (`:30-36`, ADR 0027). The threshold was
  calibrated on 18 LibriSpeech clips of 6 readers (ADR 0032) — studio speech, not the user's
  recordings.
- Embeddings are CAM++ (`diarization/SpeakerModel.kt`); the fixed-speaker-count mode already exists
  per upload (`settings`/task options) and is the current manual workaround when the auto count is
  wrong.
- Merge exists (`POST /api/tasks/{id}/speakers/merge`, `DELETE …/speakers/merges`,
  `http/SpeakerRoutes.kt:77,95`; speaker model per ADR 0028/0034); there is no split endpoint, event
  or UI.

## Proposal

1. **Measure first**: build a labelled set from the user's real recordings (and the existing
   LibriSpeech fixtures as controls), score today's auto count (speaker error rate / cluster
   purity), then test the levers:
   - recalibrate `SPEAKER_DISTANCE_THRESHOLD` and the gap window on that set;
   - a sensitivity/“expected speakers” control per task (beyond the existing fixed count);
   - embedding-side improvements only if the numbers point there.
2. **Split remedy**: an API `POST /api/tasks/{id}/speakers/{speakerId}/split` (or a turns-based
   move) that moves the selected turns to a new speaker, with an SSE event and the same
   COMPLETED-only rule as rename/merge; a UI action beside Merge.
3. Record the measured before/after and the chosen threshold in a new ADR.

## Open questions

- Which lever first: recalibration alone may fix the merge cases; a per-task sensitivity control may
  be more honest than a new default for everyone.
- The split's semantics: move selected turns to a new speaker (new id/name) vs split by a chosen
  point; how renamed speakers and merges interact with it.
- The evaluation set and metric (cluster purity, speaker error rate, or a simpler pairwise
  same/different score) and whether the user's recordings can be committed or must stay local.
- Whether the auto count should default to `maxSpeakers`-guided or offer an “expected speakers”
  field in the upload options.

## Test list (proposed; to be approved at the consultation)

- [ ] On the labelled evaluation set, the chosen auto-count rule separates the speakers in a
      measured share of the cases that fail today, without regressing the cases that pass
- [ ] A split action on a COMPLETED task moves the selected turns to a new speaker, persists, and
      the view updates in place; a non-COMPLETED task answers 409 and an unknown id 404
- [ ] Merging after a split still works (split → merge round-trips), and renamed speakers keep their
      names
- [ ] (must not break) The existing rename/merge, fixed-count and diarization suites stay green

## Acceptance criteria

- The auto count's measured quality is recorded and improved (or the chosen control makes the
  failure recoverable), and a split action exists with API, event, UI and tests.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (user report 2026-10-14); not yet
  discussed.
