# 52 — Edit the transcription: correct a segment's text, with isolated segment playback

Status: draft
Depends on: [12](../docs/specs/http-api-and-sse.md) (done; the `/turns` contract), [22](../docs/specs/speaker-rename.md) (done; the COMPLETED-only in-place-edit precedent, SSE events, contract-first examples)
Blocks: —

## Goal

Let the user fix what the transcriber got wrong: any segment of a finished transcription can be
selected and its text edited, and the edited text shows everywhere the segment shows — the
transcript view, the downloads, the speakers-off view. To make this the copy-edit workflow it is,
a segment is easy to play in isolation (its own window only) while the user edits it.

## Already decided

- Segments are stored rows (`segments`, V1; `SegmentsTable.id` autoincrement), one per whisper
  segment; nothing in the app rewrites `segment.text` today.
- Item 22's edit precedent: only COMPLETED tasks are editable (409 `not-completed` otherwise),
  unknown ids answer 404, writes are in-place row updates, an SSE event is emitted after the
  write, new endpoints extend item 12's contract with `docs/api/` examples, and the UI is driven
  by machines (ADR 0038).
- The transcript view (item 15/25/31): every segment is already a click-to-seek button
  (`TranscriptTurns` `SegmentButton` → `seekTo(startMs)`, which plays from the segment's start to
  the end of the clip). There is no stop-at-end: "play the segment in isolation" does not exist
  yet and is small new behavior on the existing `playbackMachine`/audio element.
- `/turns`'s `TurnSegmentJson` carries `startMs`, `endMs`, `text` — but **no id**; addressing an
  edit by segment needs the id on the wire (contract change plus `turns*.json` examples).
- Downloads (txt/srt/vtt) and `/turns` render the stored segment text (item 10's renderers, ADR
  0030 trims at read time) — an edit flows into them with no renderer change.
- COMPLETED tasks are never retried (item 11) — an edit cannot be lost to a rerun; FAILED and
  CANCELED tasks are retried and their segments replaced, so they are not editable (same rule item
  22 uses).

## Proposal

To be settled in the consultation. Starting point (the user's tentative design): an edit control
per segment that opens the segment's text for editing inline in the transcript; beside it a play
control that plays just that segment's window (`[startMs, endMs)`) so the user listens, then
corrects.

- **API** (extends item 12's contract; the item-22 precedent):
  - `TurnSegmentJson` gains `id` (contract change; `turns*.json` examples updated).
  - `PUT /api/tasks/{id}/segments/{segmentId}` with `{ "text": "…" }` → `200` with the updated
    turns (or the updated turn); `404 not-found` (task or segment), `409 not-completed`.
  - Text rules following item 22's amendment: the client trims; the API rejects empty/whitespace,
    over-length, or control-character text (`400 invalid-segment-text`); stored as sent.
  - Event: `segment-edited` → `{ id, segment: { id, startMs, endMs, text } }` after the write; the
    open view updates in place — a text-only change needs no `/turns` refetch (unlike a merge).
- **UI**: per segment, an "Edit" action opens an inline editor (a textarea pre-filled with the
  segment's text; Save commits, Escape discards, Ctrl+Enter saves — exact keys in the
  consultation), and a per-segment play control that seeks to `startMs`, plays, and pauses at
  `endMs` (the existing click-to-seek keeps playing past the segment). A rejected save keeps the
  old text and shows the error. Works for speakers-off tasks (speaker `null`) unchanged.
- **Out of scope**: changing timings, splitting or joining segments, speaker reassignment (item
  22's merge covers speakers), server-side undo (the textarea's native undo suffices; a saved
  edit is just re-edited). Concurrent editors: last write wins, no optimistic locking.
- **Knock-on to record when this item starts**: the "Turn rows keyed by array index" known issue
  concerns whole-array RELOADs; an in-place segment edit replaces one segment and reconciles by
  the existing per-segment keys, so it is not triggered — but the item should keep the in-place
  update (no refetch) so it stays that way.

## Open questions (to be answered in the consultation)

- Which tasks are editable — COMPLETED only (item 22's rule), confirmed?
- Text rules: max length (e.g. 2000 characters?), reject untrimmed like speaker names, or store
  as sent with only empty rejected?
- Save/cancel keys: explicit Save/Cancel buttons with Ctrl+Enter and Escape, or Enter-saves/blur-
  cancels (risk: accidental loss)?
- Isolated play: a separate play control per segment, or should clicking a segment change from
  "plays from here to the end" to "plays just this segment"? Loop-until-stopped toggle?
- After a save: in-place update from the `200`/event (no refetch) — confirmed?
- Downloads (txt/srt/vtt) must show the edited text — confirmed as expected?

## Test list (proposed; behaviors only)

- [ ] Editing a COMPLETED task's segment stores the new text; `/turns` and the view show it
- [ ] Editing a QUEUED or FAILED task's segment returns 409 `not-completed`; unknown task or segment returns 404
- [ ] Text rules: empty, whitespace-only, over-length, or control-character text is rejected 400 and leaves the stored text; a valid text is stored as sent (per the consultation)
- [ ] A speakers-off task's segment edits the same way (no speaker attached)
- [ ] The edit emits `segment-edited` after the write with the new segment; an open view updates in place without refetching `/turns`
- [ ] The downloads (txt, srt, vtt) contain the edited text after the edit
- [ ] (view) An Edit control opens an inline editor pre-filled with the segment's text; Save commits and Escape discards; a rejected save keeps the old text and shows the error
- [ ] (view) The per-segment play control plays only the segment's window — starts at its startMs and stops at its endMs; the normal player behavior (click-to-seek, full-clip playback) is unchanged
- [ ] (e2e, ADR 0046) Against the real backend, editing a segment updates the view and the downloaded txt
- [ ] (must not break) The suites, the contract-shape checks (`turns*.json` gains the segment id) and the e2e journey stay green

## Acceptance criteria

- A finished transcription's segments are editable from the transcript view, with per-segment
  isolated playback; edits persist in the database and show in the view, the downloads and the
  speakers-off view.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-14: filed from the user's request (edit button per segment; play the segment in
  isolation while editing — the typical copy-edit flow).