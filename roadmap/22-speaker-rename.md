# 22 — Rename and merge a task's speakers

Status: draft
Depends on: [09](../docs/specs/diarization.md) (done), [10](../docs/specs/output-formats.md) (done), [12](12-http-api-and-sse.md), [15](15-frontend-history-ui.md)

## Goal

Let the user give a finished transcription's speakers real names ("Speaker 1" → "Alice") and join the speakers that turned out to be one person, so the transcript view and the downloads show who really spoke.

## Already decided

- [ADR 0028 — Speakers table; segments reference their speaker by `speaker_id`](../docs/decisions/0028-speakers-table-and-segment-speaker-id.md) (item 09):
  - `speakers(task_id, speaker_id, speaker TEXT NOT NULL)`, one row per speaker of a task; segments reference it by `speaker_id`.
  - The app writes the initial name `"Speaker N"` (N = `speaker_id` + 1).
  - Naming: `speakerId` is the 0-based number, `speaker` only ever the name.
- Item 09 (`storeSpeakers`): storing a task's speakers again replaces them. That happens only when diarization reruns before the task is COMPLETED, so a rename on a completed task cannot be lost. A COMPLETED task is never retried (item 11 retries FAILED/CANCELED only), so a merge is safe there too.
- [ADR 0029 — Contract-first frontend](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md): new endpoints and events extend item 12's contract and its `docs/api/` examples.
- [ADR 0034 — Speaker labels are the stored names](../docs/decisions/0034-speaker-labels-are-stored-names.md): txt/srt/vtt already print the stored name, so a rename and a merge change the downloads with no work in item 10's renderers themselves.
- Item 12's contract: `GET /api/tasks/{id}/turns` returns `speakers` (`speakerId`, `speaker`) and turns that reference them by `speakerId`.
- User decisions (2026-10-09): a name is trimmed, 1–40 characters, no control characters; two speakers of one task may **not** share a name; a "Reset name" action restores `"Speaker N"`; only COMPLETED tasks can be renamed or merged; **merging is part of this item** ([ADR 0028](../docs/decisions/0028-speakers-table-and-segment-speaker-id.md) made the speaker a table for exactly this); merging uses a **mapping, not a segment rewrite**; the transcript is edited through a **speakers panel**; a merge is confirmed in a dialog and can be undone with **"Reset all merges"**.

## Proposal (pending approval)

- **Storage — a merge mapping (decided 2026-10-09).** A new migration (ADR 0019) adds:
  - `speaker_merge(task_id → tasks ON DELETE CASCADE, merged_id INTEGER NOT NULL, into_id INTEGER NOT NULL, PRIMARY KEY (task_id, merged_id))`, one row per speaker folded into another. `into_id` is never itself a `merged_id` for the same task (the application keeps the mapping flat).
  - **No segment is rewritten.** A segment's effective speaker is `merged_id`'s row resolved to `into_id`; the `speakers` rows are all kept, so a reset restores names and turns exactly.
  - A rename updates one `speakers` row and nothing else.
  - **The mapping is resolved in one place, at read time: `stt/db`'s diarized reads.** A repository operation returns a task's segments with each `speakerId` already resolved to its effective id, and the task's effective `speakers` (those not appearing as a `merged_id`). Everything downstream — item 09's `speakerTurns`, item 10's `renderTxt`/`cues`, item 12's `/turns`, the downloads — then needs no change and no new parameter.
    - This is the point to review first with the user: it is the smallest blast radius, but it means the read path, not the renderers, carries the merge.
- **API** (extends item 12's contract and `docs/api/`):
  - **Rename**: `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": "<name>" }` → `200` with the task's effective speakers. `{ "speaker": null }` resets that speaker's name to `"Speaker N"`, so the default-name rule stays on the server.
  - **Merge**: `POST /api/tasks/{id}/speakers/merge` with `{ "group": [<speakerId>, …], "into": <speakerId> }` → `200` with the task's effective speakers. `into` must be one of the group; the survivor keeps its id and name; every other member's `speaker_id` rows are folded into it by the mapping.
  - **Reset merges**: `DELETE /api/tasks/{id}/speakers/merges` → `200` with the task's effective speakers (all of them again).
  - Errors:

    | Status | Codes |
    | ------ | ----- |
    | `400` | `invalid-speaker-name` (not trimmed-empty, longer than 40, or a control character), `invalid-merge` (`into` not in `group`, or fewer than two speakers, or `into` already merged) |
    | `404` | `not-found` (unknown task or speaker) |
    | `409` | `not-completed` (task not COMPLETED), `duplicate-speaker-name` |
- **Events** (item 11's `SharedFlow<Event>`): `speakers-renamed` → `{ id, speakers }` after a rename or a name reset; `speakers-merged` → `{ id, speakers }` after a merge or a reset-all. Both are emitted after the write; for `speakers-merged` the client **refetches `/turns`** for the open task, because turns can join across the former boundary.
- **UI** (item 15's transcript view): a **speakers panel** above the transcript lists every effective speaker with an editable name field (saved on Enter or blur), a "Reset name" action, and a "Merge into…" action that picks the group and the survivor and confirms ("Merge Speaker 2 and Speaker 3 into Speaker 1? Their turns join Speaker 1; you can undo this."). A rejected name or merge shows the error and keeps the previous state. A merge that can be undone shows the panel's **"Reset all merges"** action.
- **Knock-ons to record when this item starts** (both are done specs):
  - item 09's spec: `speakerTurns` is unchanged, but the segments it receives are now resolved by the read path; note it in that spec's Approval log.
  - item 10's spec: `renderTxt`/`cues` unchanged for the same reason; note it.
  - item 12's contract: `/turns`' `speakers` are the **effective** speakers; add the three endpoints, the two events and the new error codes to `docs/api/`.
- **Known limitation**: merging cannot split a speaker that the diarization wrongly joined (the reverse operation); it would need its own item.

## Open questions (answered 2026-10-09)

- Name rules? → **Trimmed, 1–40 characters, no control characters.**
- Two speakers with the same name? → **Rejected** (`409 duplicate-speaker-name`).
- Back to "Speaker N"? → **A "Reset name" action**; the server writes `"Speaker N"`.
- Which tasks? → **COMPLETED only**; others `409 not-completed`.
- Merging? → **In this item**, as a group merge: `{ group: […], into: <id> }`.
- Reversibility? → **A mapping table; segments are never rewritten.**
- Undo? → **"Reset all merges"** — one action that removes the task's whole mapping.
- Editing UI? → **A speakers panel** above the transcript.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Renaming a speaker updates one `speakers` row and nothing else (no segment changes, other speakers unchanged)
- [ ] A name is trimmed; empty, longer than 40 characters, or containing a control character is rejected with 400 `invalid-speaker-name`
- [ ] A name another speaker of the same task already has is rejected with 409 `duplicate-speaker-name`
- [ ] Renaming or merging on a task that is not COMPLETED returns 409 `not-completed`; an unknown task or speaker returns 404
- [ ] Resetting a name stores `"Speaker N"` again
- [ ] A rename emits `speakers-renamed` after the row is stored
- [ ] `/turns` after a rename returns the new name and unchanged turns
- [ ] A merge folds the group's segments into the survivor's id without touching any segment row, and `/turns` then shows one speaker whose turns join across the former boundary
- [ ] The downloads (txt, srt, vtt) after a merge label every folded segment with the survivor's name
- [ ] A merge with `into` outside the group, a group of one, or an already-merged member is rejected with 400 `invalid-merge`
- [ ] A merge emits `speakers-merged` after the mapping is stored; an open view refetches `/turns`
- [ ] Reset all merges restores the original speakers, turns and downloads, and emits `speakers-merged`
- [ ] In the transcript view, the speakers panel edits a name and every turn of that speaker shows the new name; a rejected name keeps the old one
- [ ] The panel merges a group into a chosen survivor after a confirmation, and "Reset all merges" undoes it
- [ ] A `speakers-renamed` or `speakers-merged` event updates an open transcript view

## Acceptance criteria

- A rename touches exactly one `speakers` row; a merge touches no segment row (both verified in the database).
- Endpoints, events and error shapes are added to item 12's contract and `docs/api/`, and match their examples.
- The read-path resolution is in one place, and the renderers and `speakerTurns` are unchanged.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request; the feature was announced in item 09's consultation (ADR 0028).
- 2026-10-09: consultation answers approved: names trimmed, 1–40 characters, no control characters; duplicate names rejected; a "Reset name" action restores `"Speaker N"`; COMPLETED tasks only; **merging included**, as a group merge `{ group, into }`; made reversible with a **mapping table and no segment rewrite**, resolved in one place at read time; undo is **"Reset all merges"**; editing through a **speakers panel**. Test list not yet approved.
