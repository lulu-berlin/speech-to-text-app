# 15 — Frontend history UI

Status: draft
Depends on: [13](13-frontend-foundation.md)

## Goal
Let the user browse past transcriptions: see finished tasks, read a transcript with speakers and timestamps, follow it during playback with the played segment highlighted, download it in every format, listen to the task's audio, and retry failed or cancelled tasks.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): `GET /api/tasks/{id}/audio` serves one FLAC joined from the stored chunks — 16 kHz mono, not the original upload; a task canceled or failed before chunking has no audio (404).
- Item 12's contract (consultation 2026-10-09): the transcript view reads `GET /api/tasks/{id}/turns`, which returns the task's speakers (`speakerId`, stored name `speaker`) and item 09's turns referencing them by `speakerId` (ADR 0028; names become renamable later); `/audio` supports Range, so seeking and click-to-seek are possible; `DELETE /api/tasks/{id}` exists; a `task-deleted` event removes a task from the list.
- Playback with the played segment highlighted is part of this item (user, 2026-10-09). Item 12's contract puts segment times on the `/audio` timeline and keeps each turn's segments with their own `startMs`/`endMs`.
- FAILED and CANCELED tasks can be retried (`POST /api/tasks/{id}/retry`, item 11); only COMPLETED tasks have a transcript (`/turns` and downloads answer `409 not-completed` otherwise).

## Proposal (pending approval)
- History list of finished tasks (status COMPLETED, FAILED, CANCELED) with filename, status, date, duration; FAILED tasks show their error message.
- Detail view for a completed task showing speaker turns with timestamps (from `GET /api/tasks/{id}/turns`); speakers-off transcripts shown without labels.
- Download buttons per format: txt, srt, vtt, json (`GET /api/tasks/{id}/transcript?format=...`).
- Audio player (`<audio>`) sourced from `GET /api/tasks/{id}/audio` (`audio/flac`, 16 kHz mono).
- Playback highlighting: while the audio plays, the segment whose `[startMs, endMs)` contains the player's current time is highlighted inside its turn. Nothing is highlighted in a gap between segments. The highlight follows seeking and pausing.
- Retry button on FAILED and CANCELED tasks. After a retry the task leaves History and appears in the queue (item 14), via `task-updated`.
- The list updates live when a task finishes (via `useEvents`).

## Open questions for the user
- Search/filter in the history list (by filename, date, status)?
- Click-to-seek: clicking a segment/turn seeks the audio player to its start time? (Possible now: `/audio` supports Range.)
- Delete button in History, with confirmation? (Coordinated with item 14's question.)
- A task without audio (canceled or failed before chunking; the audio endpoint answers 404): hide the player, or show a "no audio" note?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] The history list shows completed, failed, and canceled tasks and no active ones
- [ ] A failed task shows its error message
- [ ] Opening a completed task shows its speaker turns with timestamps
- [ ] A speakers-off transcript is shown without speaker labels
- [ ] Each download button links to the transcript in its format
- [ ] The audio player points at the task's audio endpoint
- [ ] While the audio plays, the segment containing the current time is highlighted; in a gap between segments none is; seeking moves the highlight
- [ ] Retry is offered on failed and canceled tasks only and calls the retry endpoint; the retried task leaves the history list
- [ ] A task without stored audio (audio endpoint 404) shows no broken player
- [ ] A task finishing while the history view is open appears in the list
- [ ] A missing task (404) shows a not-found message

## Acceptance criteria
- RTL tests pass against MSW handlers.
- Checked against the running backend in item 21.
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
