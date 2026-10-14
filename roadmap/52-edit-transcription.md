# 52 — Edit the transcription: correct a segment's text, with isolated segment playback

Status: spec-approved
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

## Decided (consultation, 2026-10-14)

The user's design, confirmed: an edit control per segment opens the segment's text for editing
inline in the transcript; beside it a play control that plays just that segment's window
(`[startMs, endMs)`) so the user listens, then corrects.

- **API** (extends item 12's contract; the item-22 precedent):
  - `TurnSegmentJson` gains `id` (contract change; `turns*.json` examples updated).
  - `PUT /api/tasks/{id}/segments/{segmentId}` with `{ "text": "…" }` → `200` with the updated
    turns (or the updated turn); `404 not-found` (task or segment), `409 not-completed`
    (**COMPLETED tasks only**, item 22's rule).
  - Text rules (item 22's amendment style): **the client trims; the API rejects
    empty/whitespace-only and control-character text (`400 invalid-segment-text`); no length cap;
    stored as sent.**
  - Event: `segment-edited` → `{ id, segment: { id, startMs, endMs, text } }` after the write;
    **the open view updates in place — no `/turns` refetch** (a text edit is not a structural
    change; only merges refetch).
- **UI**: per segment, an "Edit" action opens an inline editor — **a textarea with explicit
  Save/Cancel buttons; Ctrl+Enter saves, Escape discards** — and a **separate** per-segment play
  control that seeks to `startMs`, plays, and **pauses at `endMs` (plays the window once)**; the
  existing click-to-seek keeps playing past the segment (items 9/29/31 behavior and its tests
  unchanged). A rejected save keeps the old text and shows the error. Works for speakers-off
  tasks (speaker `null`) unchanged.
- **Downloads (txt/srt/vtt) render the stored text, so edits appear in them with no renderer
  change — expected and pinned by the test list.**
- **Out of scope**: changing timings, splitting or joining segments, speaker reassignment (item
  22's merge covers speakers), server-side undo (the textarea's native undo suffices; a saved
  edit is just re-edited). Concurrent editors: last write wins, no optimistic locking.
- **Knock-on to record when this item starts**: the "Turn rows keyed by array index" known issue
  concerns whole-array RELOADs; an in-place segment edit replaces one segment and reconciles by
  the existing per-segment keys, so it is not triggered — but the item should keep the in-place
  update (no refetch) so it stays that way.

## Open questions (answered 2026-10-14)

- Which tasks are editable? → **COMPLETED only** (item 22's rule); others `409 not-completed`.
- Text rules? → **The client trims; the API rejects empty/whitespace-only and control characters;
  no length cap; stored as sent.**
- Save/cancel keys? → **Explicit Save/Cancel buttons; Ctrl+Enter saves, Escape discards** — no
  accidental loss on blur, multi-line edits work.
- Isolated play? → **A separate per-segment play control that plays `[startMs, endMs)` once**;
  click-to-seek unchanged (plays past the segment).
- After a save? → **In-place update from the `200`/`segment-edited` event; no `/turns` refetch.**
- Downloads? → **They show the edited text** (the item's test list pins it).

## Test list (proposed; behaviors only)

- [ ] Editing a COMPLETED task's segment stores the new text; `/turns` and the view show it
- [ ] Editing a QUEUED or FAILED task's segment returns 409 `not-completed`; unknown task or segment returns 404
- [ ] Text rules: empty, whitespace-only, or control-character text (client-trimmed) is rejected 400 and leaves the stored text; a valid text is stored as sent — no length cap
- [ ] A speakers-off task's segment edits the same way (no speaker attached)
- [ ] The edit emits `segment-edited` after the write with the new segment; an open view updates in place without refetching `/turns`
- [ ] The downloads (txt, srt, vtt) contain the edited text after the edit
- [ ] (view) An Edit control opens an inline textarea pre-filled with the segment's text; Save (button or Ctrl+Enter) commits, Escape discards; a rejected save keeps the old text and shows the error
- [ ] (view) The per-segment play control plays exactly the segment's window once — starts at its startMs and stops at its endMs; the normal player behavior (click-to-seek, full-clip playback) is unchanged
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
- 2026-10-14: consultation — COMPLETED tasks only; client trims, API rejects empty/whitespace-only
  and control characters, no length cap, stored as sent; inline textarea with Save/Cancel buttons
  (Ctrl+Enter saves, Escape discards); a separate per-segment play control playing the window
  once (click-to-seek unchanged); in-place update from the `200`/`segment-edited` event without a
  `/turns` refetch; downloads reflect edits. Test list approved; status `spec-approved`.