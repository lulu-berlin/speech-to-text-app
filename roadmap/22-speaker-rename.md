# 22 — Rename and merge a task's speakers

Status: in-progress
Depends on: [09](../docs/specs/diarization.md) (done), [10](../docs/specs/output-formats.md) (done), [12](../docs/specs/http-api-and-sse.md) (done), [15](../docs/specs/frontend-history-ui.md) (done)

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
- User decisions (2026-10-09): a name is trimmed, 1–40 characters, no control characters (amended 2026-10-10: the **client** trims; the API rejects a name with leading or trailing whitespace); two speakers of one task may **not** share a name; a "Reset name" action restores `"Speaker N"`; only COMPLETED tasks can be renamed or merged; **merging is part of this item** ([ADR 0028](../docs/decisions/0028-speakers-table-and-segment-speaker-id.md) made the speaker a table for exactly this); merging uses a **mapping, not a segment rewrite**; the transcript is edited through a **speakers panel**; a merge is confirmed in a dialog and can be undone with **"Reset all merges"**.

## Proposal (approved 2026-10-09)

- **Storage — a merge mapping (decided 2026-10-09).** A new migration (ADR 0019) adds:
  - `speaker_merge(task_id → tasks ON DELETE CASCADE, merged_id INTEGER NOT NULL, into_id INTEGER NOT NULL, PRIMARY KEY (task_id, merged_id))`, one row per speaker folded into another. `into_id` is never itself a `merged_id` for the same task (the application keeps the mapping flat).
  - **No segment is rewritten.** A segment's effective speaker is `merged_id`'s row resolved to `into_id`; the `speakers` rows are all kept, so a reset restores names and turns exactly.
  - A rename updates one `speakers` row and nothing else.
  - **The mapping is resolved in one place, at read time: `stt/db`'s diarized reads.** A repository operation returns a task's segments with each `speakerId` already resolved to its effective id, and the task's effective `speakers` (those not appearing as a `merged_id`). Everything downstream — item 09's `speakerTurns`, item 10's `renderTxt`/`cues`, item 12's `/turns`, the downloads — then needs no change and no new parameter.
    - This is the point to review first with the user: it is the smallest blast radius, but it means the read path, not the renderers, carries the merge.
- **API** (extends item 12's contract and `docs/api/`):
  - **Rename**: `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": "<name>" }` → `200` with the task's effective speakers. `{ "speaker": null }` resets that speaker's name to `"Speaker N"` (confirmed 2026-10-09), so the default-name rule stays on the server.
  - **Merge**: `POST /api/tasks/{id}/speakers/merge` with `{ "group": [<speakerId>, …], "into": <speakerId> }` → `200` with the task's effective speakers. `into` must be one of the group; the survivor keeps its id and name; every other member's `speaker_id` rows are folded into it by the mapping.
  - **Reset merges**: `DELETE /api/tasks/{id}/speakers/merges` → `200` with the task's effective speakers (all of them again).
  - Errors:

    | Status | Codes |
    | ------ | ----- |
    | `400` | `invalid-speaker-name` (empty, longer than 40 characters, leading or trailing whitespace, or a control character anywhere; the name is stored as sent, never trimmed by the server — amended 2026-10-10), `invalid-merge` (`into` not in `group`, or fewer than two speakers, or `into` already merged) |
    | `404` | `not-found` (unknown task or speaker) |
    | `409` | `not-completed` (task not COMPLETED), `duplicate-speaker-name` |
- **Events** (item 11's `SharedFlow<Event>`): `speakers-renamed` → `{ id, speakers }` after a rename or a name reset; `speakers-merged` → `{ id, speakers }` after a merge or a reset-all. Both are emitted after the write; for `speakers-merged` the client **refetches `/turns`** for the open task, because turns can join across the former boundary.
- **UI** (item 15's transcript view): a **speakers panel** above the transcript lists every effective speaker with an editable name field (saved on Enter or blur, **trimmed by the panel before it is sent**, amended 2026-10-10), a "Reset name" action, and a "Merge into…" action that picks the group and the survivor and confirms ("Merge Speaker 2 and Speaker 3 into Speaker 1? Their turns join Speaker 1; you can undo this."). A rejected name or merge shows the error and keeps the previous state. A merge that can be undone shows the panel's **"Reset all merges"** action.
- **Knock-ons to record when this item starts** (both are done specs):
  - item 09's spec: `speakerTurns` is unchanged, but the segments it receives are now resolved by the read path; note it in that spec's Approval log.
  - item 10's spec: `renderTxt`/`cues` unchanged for the same reason; note it.
  - item 12's contract: `/turns`' `speakers` are the **effective** speakers; add the three endpoints, the two events and the new error codes to `docs/api/`.
- **Known limitation**: merging cannot split a speaker that the diarization wrongly joined (the reverse operation); it would need its own item.

## Open questions (answered 2026-10-09)

- Name rules? → **Trimmed, 1–40 characters, no control characters.** Amended 2026-10-10: **the client trims**; the API accepts only an already-trimmed name (leading or trailing whitespace → `400 invalid-speaker-name`) and rejects a control character anywhere.
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

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] Renaming a speaker updates one `speakers` row and nothing else (no segment changes, other speakers unchanged)
- [x] A name with leading or trailing whitespace, an empty name, a name longer than 40 characters, or a name containing a control character anywhere is rejected with 400 `invalid-speaker-name`; a valid name is stored as sent (amended 2026-10-10: the client trims, the server does not)
- [x] A name another speaker of the same task already has is rejected with 409 `duplicate-speaker-name`
- [ ] Renaming or merging on a task that is not COMPLETED returns 409 `not-completed`; an unknown task or speaker returns 404
- [x] Resetting a name stores `"Speaker N"` again
- [x] A rename emits `speakers-renamed` after the row is stored
- [x] `/turns` after a rename returns the new name and unchanged turns
- [x] A merge folds the group's segments into the survivor's id without touching any segment row, and `/turns` then shows one speaker whose turns join across the former boundary
- [ ] The downloads (txt, srt, vtt) after a merge label every folded segment with the survivor's name
- [ ] A merge with `into` outside the group, a group of one, or an already-merged member is rejected with 400 `invalid-merge`
- [ ] A merge emits `speakers-merged` after the mapping is stored; an open view refetches `/turns`
- [ ] Reset all merges restores the original speakers, turns and downloads, and emits `speakers-merged`
- [ ] In the transcript view, the speakers panel edits a name and every turn of that speaker shows the new name; a rejected name keeps the old one
- [ ] The panel merges a group into a chosen survivor after a confirmation, and "Reset all merges" undoes it
- [ ] A `speakers-renamed` or `speakers-merged` event updates an open transcript view
- [ ] (discovered, from the cycle 3 decisions; confirmed 2026-10-10) A rename to the name of a speaker hidden by a merge is rejected with 409 `duplicate-speaker-name`, so resetting all merges never shows two speakers with one name
- [ ] (discovered, confirmed 2026-10-10) Merging a speaker that already absorbed others folds them too: after `{1}` was merged into 0, merging `{0, 2}` into 2 re-points 1's mapping to 2, so the mapping stays flat and 0's and 1's segments both read as 2's

## Acceptance criteria

- A rename touches exactly one `speakers` row; a merge touches no segment row (both verified in the database).
- Endpoints, events and error shapes are added to item 12's contract and `docs/api/`, and match their examples.
- The read-path resolution is in one place, and the renderers and `speakerTurns` are unchanged.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10 (workspace, cycle 1, test-list item 1): read-path proposal confirmed by the user: `V5__speaker_merge` migration plus `SegmentRepository.findDiarized(taskId)` resolving the mapping in one read transaction, consumed only by TranscriptRoutes; `findByTask`/`findSpeakers` stay raw; renderers and `speakerTurns` unchanged. Rename test first, as confirmed.
- 2026-10-10 (workspace, cycle 1): red — `gradle test --tests 'stt.db.SegmentRepositorySpec'`: compilation failed, "Unresolved reference 'renameSpeaker'" (`SegmentRepositorySpec.kt:204`). Fixture: task with three segments stored as `[0, 1, 0]` plus another task with speaker 1; a rename of speaker 1 to `Alice` must change only that row (segments read back identically, speaker 0 and the other task untouched).
- 2026-10-10: no refactoring after cycle 1 (user). Item 2 confirmed as the next test.
- 2026-10-10 (workspace, cycle 2, test-list item 2): new `SpeakerRoutesSpec` — a COMPLETED task with speakers 0 and 1 behind a paused queue; `PUT /api/tasks/{id}/speakers/1` with `""`, `"   "`, 41 × `A`, `"Al\tice"` and `"Bob\u007F"` (tab and DEL where trimming leaves them) must each answer `400 invalid-speaker-name` with the `error.json` shape and leave the stored names; 40 × `A` answers `200` shaped like the new `docs/api/speakers.json`; `"  Alice  "` answers `200` with speakers `Speaker 1`, `Alice`, and `/turns` agrees. Red: `expected:<400 Bad Request> but was:<404 Not Found>` (no route; item 02's bare 404). Green: new `http/SpeakerRoutes.kt` (`PUT /api/tasks/{id}/speakers/{speakerId}`: trim, 1–40 characters, `Char.isISOControl` rejected, then `renameSpeaker` and `200 { speakers }`), registered in `sttApp`. Contract extended in the same commit: `docs/api/speakers.json`, its README row, and item 12's contract section (Speakers bullet, route response, `invalid-speaker-name`). Status and existence guards (item 4) and the `null` reset (item 5) are left to their cycles: until then a non-numeric id or an unknown speaker answers `500 internal`.
- 2026-10-10 (cycle 2 checkpoint, user decisions): refactor approved — `SpeakerJson` moved next to `SpeakersJson` in `SpeakerRoutes.kt` (`Refactor:` commit). Spec change: trimming moves to the client; the API stores a name as sent and rejects leading or trailing whitespace, empty, over 40 characters, or a control character anywhere (one code, `400 invalid-speaker-name`; no ADR, recorded here and in item 12's contract). This invalidates cycle 2's `"  Alice  "` → `Alice` expectation, so test-list item 2 is amended, unticked, and reworked now as cycle 3, before item 3; the panel (item 13) trims before sending.
- 2026-10-10 (workspace, cycle 3, amended test-list item 2): `SpeakerRoutesSpec` amended to the new rule — the rejected names now include `"  Alice  "`, `" Alice"`, `"Alice "` and `"\tAlice"` besides cycle 2's `""`, `"   "`, 41 × `A`, `"Al\tice"`, `"Bob\u007F"`; the valid `"Alice Smith"` (inner space) must be stored and returned exactly as sent. Red: `expected:<400 Bad Request> but was:<200 OK>` at the first padded name, `"  Alice  "` (the two names before it were already rejected). Green: the route no longer trims; `isValidSpeakerName(name)` = 1–40 characters, `name == name.trim()`, no `Char.isISOControl`; the 400 message names the untrimmed case. `gradle check` green.
- 2026-10-10 (cycle 3 checkpoint, user decisions): no refactoring. Duplicate names (item 3) are compared **case-insensitively**; renaming a speaker to its own current name is a `200` no-op; the check covers **all** of the task's speakers, merged (hidden) ones included. The last point cannot be tested before merging exists, so it is appended as a discovered test-list item, to be confirmed.
- 2026-10-10 (workspace, cycle 4, test-list item 3): new scenario in `SpeakerRoutesSpec` — a COMPLETED task with speakers 0–2 and another COMPLETED task; after renaming speaker 0 to `Alice` (and the other task's speaker 0 to `Bob`), renaming speaker 1 to `Alice` and `ALICE`, speaker 2 to `alice`, and speaker 1 to `speaker 3` (an untouched default name) must each answer `409 duplicate-speaker-name` with the `error.json` shape and leave the names; then speaker 0 to `Alice` and `ALICE` (own name) and speaker 2 to `Bob` (another task's name) answer `200`, ending at `ALICE`, `Speaker 2`, `Bob`. Red: `expected:<409 Conflict> but was:<200 OK>` at speaker 1 → `Alice`. Green: `SegmentRepository.renameSpeaker` returns the new `RenameOutcome` (`Renamed` | `DuplicateName(existing)`) and checks every `speakers` row of the task except the renamed one with `equals(ignoreCase = true)` in the same write transaction as the update (single writer, so two concurrent renames cannot both pass); the route maps `DuplicateName` to `409` naming the existing speaker. Contract: rename bullet and `409` row in item 12's contract. `gradle check` green.
- 2026-10-10 (cycle 4 checkpoint, user decisions): two refactors approved, each its own commit — `SegmentRepository`'s two `speakers`-row mappings share a private `ResultRow.toSpeaker()`; `SpeakerRoutesSpec`'s repeated status + `error.json` shape + code assertion became the file-private `HttpResponse.shouldBeError(status, code)`. The discovered item (hidden-speaker duplicate) is confirmed. Item 5 next; a reset name is checked for duplicates like any other (stated with the choice).
- 2026-10-10 (workspace, cycle 5, test-list item 5): new scenario in `SpeakerRoutesSpec` — a COMPLETED task with speakers 0–2; speaker 1 renamed to `Alice`, then `{ "speaker": null }` must answer `200` with `Speaker 1`, `Speaker 2`, `Speaker 3` and `/turns` agrees; then speaker 2 → `Carol` and speaker 0 → `Speaker 3`, after which resetting speaker 2 (whose default is `Speaker 3`) must answer `409 duplicate-speaker-name` and leave `Speaker 3`, `Speaker 2`, `Carol`. Red: `expected:<200 OK> but was:<500 Internal Server Error>` (the request type's `speaker: String` cannot take `null`). Green: `RenameSpeakerRequest.speaker` is nullable; a null name becomes `"Speaker ${speakerId + 1}"` and goes through `renameSpeaker` (and its duplicate check) like a sent name; only a sent name is validated. The default-name template now exists twice (`storeSpeakers`, the route) — offered as a refactor. Contract: the rename bullet states the reset. `gradle check` green.
- 2026-10-10 (cycle 5 checkpoint, user decisions): refactor approved — the default name has one home, `stt.db.defaultSpeakerName(speakerId)` beside `Speaker`, used by `storeSpeakers` and the reset (`Refactor:` commit). Item 6 next.
- 2026-10-10 (workspace, cycle 6, test-list item 6): new scenario in `SpeakerRoutesSpec` over a real Netty server (`SseServer`, as `EventsSpec`; it gained a JSON-body `command` overload) — a COMPLETED task with speakers 0 and 1; after the snapshot, an untrimmed rename (`400`) and then `Alice` (`200`) must yield exactly one next frame, `speakers-renamed`, shaped like the new `docs/api/speakers-renamed.json` and equal to `{ id, speakers: [Speaker 1, Alice] }`; a reset (`null`) yields another with `Speaker 1`, `Speaker 2`. Red: `java.util.concurrent.TimeoutException` at the frame after the stored rename (`SpeakerRoutesSpec.kt:147`) — nothing was emitted. Green: `Event.SpeakersRenamed(id, speakers)`; the route now renames through the new `ProcessingEngine.renameSpeaker`, which writes via `SegmentRepository.renameSpeaker` and, only on `Renamed`, emits the speakers read back from the database (the established pattern: mutations that publish go through the engine, like `deleteTask`); `EventsRoutes` names it `speakers-renamed` with the `SpeakersEventJson` payload; `EventsOverflowSpec`'s exhaustive `when` gained the branch. Contract: `docs/api/speakers-renamed.json`, its README row, and the Events list of item 12's contract. `gradle check` green.
- 2026-10-10 (cycle 6 checkpoint, user decisions): refactor approved — `Speaker.toJson()` beside `SpeakerJson` replaces the three `SpeakerJson(it.speakerId, it.speaker)` mappings (SpeakerRoutes, EventsRoutes, TranscriptRoutes; `Refactor:` commit). Item 7 next.
- 2026-10-10 (workspace, cycle 7, test-list item 7): new scenario in `SpeakerRoutesSpec` — a COMPLETED task whose three segments are spoken by speakers 0, 0, 1; `/turns` is read, speaker 0 renamed to `Alice`, `/turns` read again: shaped like `turns.json`, `speakers` = `Alice`, `Speaker 2`, `turns` equal to the turns before the rename, and their `speakerId`s `0`, `1` (segments 0–1 one turn, segment 2 the other). Green on arrival: `/turns` reads the stored names, and a rename touches no segment (cycle 1). Red recorded against two temporary, uncommitted mutations, each reverted: (1) `/turns` labelling speakers with `defaultSpeakerName` instead of the stored name — `expected:<[… ("speaker", "Alice")], …]> but was:<[… ("speaker", "Speaker 1")], …]>` (`SpeakerRoutesSpec.kt:179`; cycles 2 and 3 failed the same way); (2) `renameSpeaker` also setting every segment of the task to the renamed `speakerId` — `Element differ at index: [0]`, `Missing elements from index 1` (the turns collapsed into one; cycle 1's repository test failed too). No production change. Contract: the `/turns` `speakers` sentence no longer says "until a rename feature exists"; it states that a rename changes only the name, never the turns. `gradle check` green.
- 2026-10-10 (cycle 7 checkpoint, user decisions): refactor approved — `SpeakerRoutesSpec` reads `/turns` through one file-private `HttpClient.turnsOf(id)` (`speakersOf` uses it; `Refactor:` commit). Merging a speaker that already absorbed others is **allowed and re-pointed** (the mapping stays flat); appended as a discovered test-list item. Item 8 next.
- 2026-10-10 (workspace, cycle 8, test-list item 8): new scenario in `SpeakerRoutesSpec` — a COMPLETED task whose four segments are spoken by speakers 0, 1, 0, 2, speaker 0 renamed to `Alice`; `POST /api/tasks/{id}/speakers/merge` with `{ group: [0, 1], into: 0 }` must answer `200` shaped like `speakers.json` with the effective speakers `0 Alice`, `2 Speaker 3`; `/turns` must equal the body derived by hand (one `Alice` turn over segments 0–2, 0–3 000 ms, `"Hello. Hi. Hey."`; then speaker 2's `"Bye."`); and, read from the database after the app stopped, every segment row is as seeded (`0, 1, 0, 2`) and all three `speakers` rows remain. Red: `expected:<200 OK> but was:<404 Not Found>` (no merge route). Green: the confirmed read path — new `V5__speaker_merge.sql` (`speaker_merge(task_id → tasks ON DELETE CASCADE, merged_id, into_id, PK (task_id, merged_id))`) and `SpeakerMergeTable`; `SegmentRepository.mergeSpeakers(taskId, group, into)` inserts one mapping row per other member in one write transaction and touches no segment or speaker; `SegmentRepository.findDiarized(taskId)` → `DiarizedRead(segments, speakers)` reads mapping, segments and speakers in one read transaction, resolves each `speakerId` with one lookup and drops merged speakers; `/turns` (speakers on) uses it, `speakerTurns` unchanged; `POST …/speakers/merge` answers the effective speakers. The segment and speaker queries now exist twice (`findByTask`/`findSpeakers` and `findDiarized`) — offered as a refactor. `DatabaseSpec`'s first-open test now expects 5 migrations and the `speaker_merge` table (schema change, ADR 0019). Validation (item 10), guards (item 4), the event (item 11) and the downloads (item 9) are left to their cycles. Contract: merge endpoint and route response, `/turns` `speakers` = effective speakers, README row. `gradle check` green.
- 2026-10-09: item drafted at the user's request; the feature was announced in item 09's consultation (ADR 0028).
- 2026-10-09: consultation answers approved: names trimmed, 1–40 characters, no control characters; duplicate names rejected; a "Reset name" action restores `"Speaker N"`; COMPLETED tasks only; **merging included**, as a group merge `{ group, into }`; made reversible with a **mapping table and no segment rewrite**, resolved in one place at read time; undo is **"Reset all merges"**; editing through a **speakers panel**. Test list not yet approved.
- 2026-10-09: test list (15 items) approved by the user; `{"speaker": null}` confirmed as the name reset; status `spec-approved`.
