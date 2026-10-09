# 22 — Rename a task's speakers

Status: draft
Depends on: [09](09-diarization.md), [12](12-http-api-and-sse.md), [15](15-frontend-history-ui.md)

## Goal

Let the user give a finished transcription's speakers real names ("Speaker 1" → "Alice"), stored in the database, so the transcript view, and the downloads if item 10 decides so, show the names.

## Already decided

- [ADR 0028 — Speakers table; segments reference their speaker by `speaker_id`](../docs/decisions/0028-speakers-table-and-segment-speaker-id.md) (item 09):
  - `speakers(task_id, speaker_id, speaker TEXT NOT NULL)`, one row per speaker of a task. Segments reference it by `speaker_id`.
  - A rename updates one row and touches no segment.
  - The app writes the initial name `"Speaker N"` (N = `speaker_id` + 1).
  - Naming: `speakerId` is the 0-based number, `speaker` only ever the name.
- Item 09 (`storeSpeakers`): storing a task's speakers again replaces them. That happens only when diarization reruns after a crash, before the task is COMPLETED, so a rename on a completed task cannot be lost.
- [ADR 0029 — Contract-first frontend](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md): new endpoints and events extend item 12's contract and its `docs/api/` examples.
- Item 12's contract: `GET /api/tasks/{id}/turns` returns `speakers` (`speakerId`, `speaker`), and turns reference them by `speakerId` only. The view resolves names, so a rename changes no turn.

## Proposal (pending approval)

- **Storage.** `SegmentRepository` (or a speakers repository, whichever `stt/db` uses by then) gets one operation that renames a speaker of a task in a single write transaction:
  - it updates one `speakers` row;
  - an unknown task or speaker fails;
  - no other row changes.
- **API** (extends item 12's contract and `docs/api/`):
  - `PUT /api/tasks/{id}/speakers/{speakerId}` with body `{ "speaker": "<name>" }` → `200` with the task's speakers `[{ "speakerId", "speaker" }]`.
  - Errors:

    | Status | Codes |
    | ------ | ----- |
    | `400` | `invalid-speaker-name` |
    | `404` | `not-found` (unknown task or speaker) |
    | `409` | `not-completed` (task not COMPLETED) |
- **Event.** `speakers-renamed` → `{ "id", "speakers": [{ "speakerId", "speaker" }] }`, so open views update without a refetch. The engine's `SharedFlow<Event>` (item 11) carries it; the event is emitted after the rename is stored.
- **UI** (History detail view, item 15): the speaker names are editable in place. Saving calls the endpoint; a rejected name shows the error and keeps the old name.
- **Downloads.** Whether txt/srt/vtt print the stored name follows item 10's open question "Speaker names". The json download keeps the 0-based `speaker` number (oracle format).

## Open questions for the user

- Name rules: trimmed, non-empty, maximum length (e.g. 100 characters)? Must names be unique within a task, or may two speakers share a name (for example, to merge two clusters of the same person by name only)?
- Reset: is an empty name a way back to `"Speaker N"`, or is reset a separate action (or not offered)?
- Merging speakers (two clusters that are the same person): out of scope here (it would rewrite segments), or part of this item?
- Is renaming allowed only for COMPLETED tasks (proposed, consistent with item 09's replace-on-rerun), or also for FAILED/CANCELED tasks that kept segments with speakers?
- Downloads: settled by item 10's open question, or revisited here once names can differ from `"Speaker N"`?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Renaming a speaker changes its stored name and nothing else (other speakers and all segments unchanged)
- [ ] Renaming a speaker of an unknown task or an unknown speaker id fails and stores nothing
- [ ] `PUT /api/tasks/{id}/speakers/{speakerId}` returns the task's speakers with the new name
- [ ] An invalid name is rejected with 400 `invalid-speaker-name`
- [ ] Renaming on a task that is not COMPLETED returns 409 `not-completed`
- [ ] A rename emits `speakers-renamed` after it is stored
- [ ] `/turns` after a rename returns the new name in `speakers` and unchanged turns
- [ ] In the History detail view, editing a speaker's name saves it and every turn of that speaker shows the new name
- [ ] A rejected rename shows the error and keeps the old name in the view
- [ ] A `speakers-renamed` event updates an open transcript view

## Acceptance criteria

- The rename touches exactly one `speakers` row, verified in the database.
- Endpoint, event and error shapes are added to item 12's contract and `docs/api/`, and match their examples.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request; the feature was announced in item 09's consultation (ADR 0028), which made the speaker its own table for it.
