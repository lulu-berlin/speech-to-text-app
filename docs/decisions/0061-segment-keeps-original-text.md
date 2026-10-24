# 0061 — A segment keeps whisper's original text, and Revert restores it

Date: 2026-10-23
Status: accepted

## Context

Item 52 lets a reader correct a transcript segment. The first contract stored only the
segment's current text (`PUT /api/tasks/{id}/segments/{segmentId}` overwrote it), so a saved
correction destroyed whisper's line: there was no way back to the transcription, short of
re-transcribing the file.

The user asked for an undo next to Save ("Revert … go back to the original transcription"),
and drew the analogy with un-merging speakers (item 22): undo goes back to the beginning, there
for a whole task, here for one segment.

## Decision

- **Each `segments` row keeps the text it was first written with** — whisper's line, after the
  chunk's offsets were applied — in `original_text`. An edit changes only `text`; a later edit
  does not touch `original_text` either. Rows that predate the column are backfilled with their
  current text (the migration cannot know better).
- **`POST /api/tasks/{id}/segments/{segmentId}/revert`** stores `original_text` back into `text`
  and answers like an edit: `200` with the task's updated turns (the same body `/turns` returns)
  and the `segment-edited` event, so every open view follows. The refusals are the edit's:
  `409 not-completed` for a task that is not COMPLETED, `404 not-found` for an unknown task or a
  segment that is not that task's.
- **The turns contract says whether a segment differs from its original**: each segment carries
  `edited` (a boolean, `text` against `original_text`, both trimmed). The view offers Revert only
  when it is true; nothing else exposes the original text.
- **The downloads keep rendering the stored text** — the edited line, or the original after a
  revert. They never read `original_text`, so a correction is what the txt/srt/vtt contain.

## Consequences

- One nullable column and one migration; the original is stored once and never rewritten.
- Only the first line is recoverable: repeated edits are not a history, and there is no undo of
  a revert beyond editing again.
- A re-transcription (a retried task) writes new rows with their own originals.
- The contract gains a field on every segment (`edited`) and one route; `docs/api/turns*.json`
  and the route table show both.
