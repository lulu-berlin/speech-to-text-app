# 14 — Frontend queue UI

Status: draft
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](24-visual-design.md)

## Goal
Let the user upload many audio files and manage the work queue from the browser: see live status and progress, pause/resume/cancel individual tasks, reorder the queue, and pause the whole queue.

## Already decided
- [Item 24 design system](24-visual-design.md): the Queue view is built with its tokens and primitives (buttons, status badge, progress bars, empty state), not with ad-hoc styles; item 25 does the final visual pass.
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pause/resume semantics the UI exposes.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): status/progress arrive via SSE; commands go via REST.
- Item 12's contract (consultation 2026-10-09): speaker inputs 1–20; uploads limited to the extension allow-list; `DELETE /api/tasks/{id}` works on any task (a pending one is cancelled first); a duplicate upload answers `409 duplicate-audio` with the existing `taskId`. Item 11: two progress bars, `transcriptionProgress` and `diarizationProgress`; the diarization bar runs once per `diarizationStage` (`EMBEDDING`, then `CLUSTERING`) and is hidden when `diarizationProgress` is null.

## Proposal (pending approval)
- Drag-and-drop multi-file upload (plus a file picker) with per-upload options: language, speakers (`auto` / `off` / fixed count), max speakers; each file is sent as one `POST /api/tasks`.
- Queue list showing each task's filename, status, phase (CHUNKING / TRANSCRIBING / DIARIZING) and two progress bars, updated live from item 13's connection machine: transcription, and diarization, which fills once for embedding and once for clustering, labelled by `diarizationStage`, and is hidden when `diarizationProgress` is null.
- Per-task controls: pause, resume, cancel (`POST /api/tasks/{id}/pause|resume|cancel`), shown only when `docs/api/task-transitions.json` allows the command for the task's status, and sent through item 13's command machine (ADR 0031).
- Drag reorder of non-terminal tasks (`@dnd-kit` proposed) calling `PUT /api/queue/order` with the full ordered id list; on a rejected reorder (409) the list reverts to the server order.
- Queue pause toggle (`POST /api/queue/pause|resume`) reflecting `GET /api/queue` state.

## Open questions for the user
- Confirm `@dnd-kit` for drag reorder, or prefer another library / up-down buttons?
- Which per-upload options are shown by default vs behind an "advanced" toggle, and whether options can be set once for a whole batch?
- Confirmation dialog before cancel: yes/no?
- Delete button in the queue (any task; a pending one is cancelled by the server first): offered here, in History (item 15), or both? Confirmation dialog?
- How is a duplicate upload shown: error message with a link to the existing task?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] Dropping several files uploads each one with the chosen options
- [ ] A new task appears in the queue list after upload
- [ ] A `task-progress` event updates that task's two progress bars; the diarization bar is hidden when diarization does not apply
- [ ] A `task-updated` event updates that task's status and phase
- [ ] Pressing pause on a running task calls the pause endpoint and shows it as paused
- [ ] Pressing resume on a paused task calls the resume endpoint
- [ ] Pressing cancel calls the cancel endpoint and shows the task as canceled
- [ ] Controls for disallowed transitions are not offered (e.g. no pause or cancel on a completed task)
- [ ] Reordering sends the full ordered id list to `PUT /api/queue/order`
- [ ] A rejected reorder restores the server order and shows the error message
- [ ] The queue pause toggle calls pause/resume and reflects the queue state
- [ ] An upload error is shown to the user (unsupported format, duplicate with its existing task)

## Acceptance criteria
- RTL interaction tests for each control pass against MSW handlers.
- Checked against the running backend in item 21.
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
