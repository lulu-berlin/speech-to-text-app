# 15 — Frontend history UI

Status: draft
Depends on: [13](13-frontend-foundation.md)

## Goal
Let the user browse past transcriptions: see finished tasks, read a transcript with speakers and timestamps, download it in every format, and listen to the original audio.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): audio is served from the database via `GET /api/tasks/{id}/audio`.

## Proposal (pending approval)
- History list of finished tasks (status COMPLETED, FAILED, CANCELED) with filename, status, date, duration; FAILED tasks show their error message.
- Detail view for a completed task showing speaker turns with timestamps (from `GET /api/tasks/{id}/transcript?format=json`); speakers-off transcripts shown without labels.
- Download buttons per format: txt, srt, vtt, json (`GET /api/tasks/{id}/transcript?format=...`).
- Audio player (`<audio>`) sourced from `GET /api/tasks/{id}/audio`.
- The list updates live when a task finishes (via `useEvents`).

## Open questions for the user
- Search/filter in the history list (by filename, date, status)?
- Click-to-seek: clicking a segment/turn seeks the audio player to its start time?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] The history list shows completed, failed, and canceled tasks and no active ones
- [ ] A failed task shows its error message
- [ ] Opening a completed task shows its speaker turns with timestamps
- [ ] A speakers-off transcript is shown without speaker labels
- [ ] Each download button links to the transcript in its format
- [ ] The audio player points at the task's audio endpoint
- [ ] A task finishing while the history view is open appears in the list
- [ ] A missing task (404) shows a not-found message

## Acceptance criteria
- RTL tests pass against MSW handlers.
- Against the running backend: a completed transcription appears in History, its turns are readable, every download produces the matching file, and the audio plays.
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
