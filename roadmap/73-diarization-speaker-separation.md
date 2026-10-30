# 73 — Split a speaker: move selected turns to a new speaker

Status: spec-approved
Depends on: —
Blocks: —

## Goal

When diarization puts distinct speakers under one id, the user can fix it: a **split** action moves
the selected turns to a new speaker, the remedy that wrongly merged speakers lack today (merge
exists for the opposite case, split does not). Improving the automatic detection itself is item
[83](83-diarization-accuracy.md).

## Evidence

- **User report 2026-10-14**: diarization sometimes puts distinct speakers under one id and
  sometimes splits one speaker across ids. A split has the merge remedy; an unseparated pair has no
  split tool, and re-running reproduces the same clustering for the same input.
- Improving the detection that produces these errors (the speaker count, the embedding windows,
  the clustering) is item [83](83-diarization-accuracy.md) since 2026-10-29; it was this item's
  second half until then.
- Embeddings are CAM++ (`diarization/SpeakerModel.kt`); the fixed-speaker-count mode already exists
  per upload (`settings`/task options) and is the current manual workaround when the auto count is
  wrong.
- Merge exists (`POST /api/tasks/{id}/speakers/merge`, `DELETE …/speakers/merges`,
  `http/SpeakerRoutes.kt:77,95`; speaker model per ADR 0028/0034); there is no split endpoint, event
  or UI.

## Proposal

1. **Split remedy first**: `POST /api/tasks/{id}/speakers/{speakerId}/split` moves the selected turns
   (one or more) to a new speaker with a default name, under the same COMPLETED-only rule as
   rename/merge, published as an SSE event, with a UI action beside Merge.
2. The auto-count measurement that was step 2 here (a labelled local set, the threshold and gap
   window, a sensitivity control) moved to item [83](83-diarization-accuracy.md) on 2026-10-29.

## Decided (consultation, 2026-10-28)

- **Split first, measurement second** in the same item: the missing remedy is the user-visible gap
  and has no data dependency; the auto-count work follows on the labelled set. *(Superseded
  2026-10-29: the measurement is item 83; this item is the split action only.)*
- **A split moves the selected turns** to a new speaker (default name, renameable), one or more
  turns per action and repeated splits allowed. It is COMPLETED-only (409 otherwise, 404 for an
  unknown speaker id), publishes its own SSE event, and renames, merges and split→merge keep
  working across it.

## Test list (approved; behaviors only)

- [ ] A split action on a COMPLETED task moves the selected turns to a new speaker, persists, and
      the view updates in place; a further split works, a non-COMPLETED task answers 409 and an
      unknown speaker id 404
- [ ] Merging after a split still works (split → merge round-trips)
- [ ] Renamed speakers keep their names across a split, and a split speaker can be renamed
- [ ] (must not break) The existing rename/merge, fixed-count and diarization suites stay green

## Acceptance criteria

- A split action exists with API, event, UI and tests.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (user report 2026-10-14); not yet
  discussed.
- 2026-10-28: consultation with the user: split remedy first (moving selected turns to a new
  speaker), then the auto-count measurement on a local-only labelled set with only aggregate scores
  committed. Test list approved as written; status set to spec-approved.
- 2026-10-30 (amendment, the user): the detection measurement moved to item
  [83](83-diarization-accuracy.md); this item is the split action only. Proposal step 2, test-list
  item 4 and the two measurement decisions left; the title, Goal and acceptance criteria were
  narrowed to match. Status stays spec-approved.
