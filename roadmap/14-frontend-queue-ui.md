# 14 — Frontend queue UI

Status: draft
Depends on: [13](13-frontend-foundation.md)

## Goal
Let the user upload many audio files and manage the work queue from the browser: see live status and progress, pause/resume/cancel individual tasks, reorder the queue, and pause the whole queue.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pause/resume semantics the UI exposes.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): status/progress arrive via SSE; commands go via REST.

## Proposal (pending approval)
- Drag-and-drop multi-file upload (plus a file picker) with per-upload options: language, speakers (`auto` / `off` / fixed count), max speakers; each file is sent as one `POST /api/tasks`.
- Queue list showing each task's filename, status, phase (CHUNKING / TRANSCRIBING / DIARIZING) and a progress bar, updated live from `useEvents`.
- Per-task controls: pause, resume, cancel (`POST /api/tasks/{id}/pause|resume|cancel`), shown only when the transition is allowed for the task's status.
- Drag reorder of non-terminal tasks (`@dnd-kit` proposed) calling `PUT /api/queue/order` with the full ordered id list; on a rejected reorder (409) the list reverts to the server order.
- Queue pause toggle (`POST /api/queue/pause|resume`) reflecting `GET /api/queue` state.

## Open questions for the user
- Confirm `@dnd-kit` for drag reorder, or prefer another library / up-down buttons?
- Which per-upload options are shown by default vs behind an "advanced" toggle, and whether options can be set once for a whole batch?
- Confirmation dialog before cancel: yes/no?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] Dropping several files uploads each one with the chosen options
- [ ] A new task appears in the queue list after upload
- [ ] A `task-progress` event updates that task's progress bar
- [ ] A `task-updated` event updates that task's status and phase
- [ ] Pressing pause on a running task calls the pause endpoint and shows it as paused
- [ ] Pressing resume on a paused task calls the resume endpoint
- [ ] Pressing cancel calls the cancel endpoint and shows the task as canceled
- [ ] Controls for disallowed transitions are not offered (e.g. no pause or cancel on a completed task)
- [ ] Reordering sends the full ordered id list to `PUT /api/queue/order`
- [ ] A rejected reorder restores the server order and shows the error message
- [ ] The queue pause toggle calls pause/resume and reflects the queue state
- [ ] An upload error is shown to the user

## Acceptance criteria
- RTL interaction tests for each control pass against MSW handlers.
- Against the running backend: uploading several files queues them in order; pause/resume/cancel and reorder visibly take effect; progress bars advance live without page reload.
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
