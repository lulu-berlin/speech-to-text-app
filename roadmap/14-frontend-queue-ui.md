# 14 — Frontend queue UI

Status: spec-approved
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](../docs/specs/design-system.md) (done)

## Goal
Let the user upload many audio files and manage the work queue from the browser: see live status and progress, pause/resume/cancel/delete individual tasks, reorder the queue, and pause the whole queue.

## Already decided
- [Item 24 design system](24-visual-design.md) ([ADR 0033](../docs/decisions/0033-design-system-tokens-radix-plex.md)): the Queue view is built with its tokens and primitives (Button, StatusBadge, ProgressBar, EmptyState, List/ListRow, DropdownMenu, Switch, ConfirmDialog, Page/Stack/Row), not with ad-hoc styles; item 25 does the final visual pass (comfortable rows, phase as a text label, empty state with guidance).
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0004 — Chunked transcription, SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pause/resume semantics the UI exposes.
- [ADR 0007 — SSE live updates](../docs/decisions/0007-sse-live-updates.md): status/progress arrive via SSE; commands go via REST.
- [ADR 0029](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md) and [ADR 0031](../docs/decisions/0031-frontend-routing-react-router-state-xstate.md): built against item 12's contract with MSW mocks; commands go through item 13's command machine, which only offers what `docs/api/task-transitions.json` allows.
- Item 12's contract: speaker inputs 1–20; uploads limited to the extension allow-list; `language` from [`docs/api/languages.json`](../docs/api/languages.json), default `auto`; `DELETE /api/tasks/{id}` works on any task (a pending one is cancelled first); a duplicate upload answers `409 duplicate-audio` with the existing `taskId`. Item 11: two progress bars, `transcriptionProgress` and `diarizationProgress`; the diarization bar runs once per `diarizationStage` (`EMBEDDING`, then `CLUSTERING`) and is hidden when `diarizationProgress` is null.
- The queue shows pending tasks (QUEUED, RUNNING, PAUSED) in queue order; finished tasks are in History (item 15).

## Proposal (approved 2026-10-09)
- **Upload** (decided 2026-10-09):
  - A drop area plus a file picker. One **options panel for the whole batch** above it: language (a searchable select over `docs/api/languages.json`, default `auto`), speakers (`auto` / `off` / fixed count 1–20), and max speakers (1–20, default 8) behind an "advanced" toggle. Every file dropped or picked uses the current settings.
  - Files upload **one at a time, in drop order**, each with its own **upload progress** (bytes sent). Item 13's client `uploadTask` is extended to send the options and to report progress, which needs `XMLHttpRequest` (fetch has no upload progress); it stays the only place that talks to `POST /api/tasks`.
  - Each file has an upload row while it uploads; an error stays **inline on that row**: unsupported format, invalid input, or a duplicate. A duplicate shows "Already uploaded" with a **link to the existing task**: its queue row if it is pending, its transcript (`/history/:id`) if it is finished.
- **Queue list**: each pending task's filename, status badge, phase as a text label, and the two progress bars (transcription with a percentage; diarization with its stage label, hidden when `diarizationProgress` is null), updated live from item 13's connection machine.
- **Per-task controls**: pause, resume, cancel (`POST /api/tasks/{id}/pause|resume|cancel`), shown only when `docs/api/task-transitions.json` allows the command for the task's status, sent through item 13's command machine; a failed command shows its error inline on the row.
  - **Cancel asks for confirmation only when work would be lost**: a RUNNING task, or a PAUSED task that has started (`startedAt` set). A QUEUED or never-started PAUSED task is cancelled directly (it can be retried from History).
  - **Delete** (`DELETE /api/tasks/{id}`) is in each row's menu and **always asks for confirmation**: it removes the task, transcript and audio for good (the same in History, item 15).
- **Reorder** (decided 2026-10-09): **drag handles with `@dnd-kit`** (keyboard dragging included) **plus "Move to top / up / down / bottom"** in each row's menu (item 24's DropdownMenu), which is precise on a phone. Either path sends the full ordered id list to `PUT /api/queue/order`; a rejected reorder (409) reverts to the server order and shows the error.
- **Queue pause**: item 24's Switch, calling `POST /api/queue/pause|resume` and reflecting the queue's `paused` flag.
- **Finished tasks** (decided 2026-10-09): when a task leaves the queue because it finished, a **short notice with a link** appears: "interview.mp3 transcribed — open" (to `/history/:id`), or "… failed — see History" / "… canceled". The row leaves the queue.
- **New dependency**: `@dnd-kit` (core + sortable), changing `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash (user-verified `nix build .`, ADR 0012). The notice reuses item 24's primitives; no toast library unless approved.

## Open questions (answered 2026-10-09)
- Reorder? → **`@dnd-kit` drag plus menu actions.**
- Upload options? → **One options panel for the batch.**
- Language input? → **A select over a shared list extracted from whisper-cli** (`docs/api/languages.json`), **default `auto`**.
- Large uploads? → **Per-file upload progress, one file at a time.**
- Confirm before cancel? → **Only for a RUNNING or started PAUSED task.**
- Delete? → **In the queue and in History, always with confirmation.**
- Duplicate upload? → **Inline error on that file, with a link to the existing task.**
- Finished tasks? → **A short notice with a link**; the row leaves the queue.

## Remaining open questions
None.

## Test list (written with the user; Canon TDD step 1)
Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.
- [ ] Dropping several files uploads them one at a time in drop order, each with the batch's current options
- [ ] The language select offers the codes from `docs/api/languages.json` and defaults to `auto`; speakers and max speakers accept only 1–20
- [ ] Each uploading file shows its upload progress
- [ ] An upload error stays on that file's row: unsupported format, invalid input, and a duplicate with a link to the existing task (queue row if pending, transcript if finished)
- [ ] A new task appears in the queue list after upload
- [ ] A `task-progress` event updates that task's two progress bars; the diarization bar shows its stage and is hidden when diarization does not apply
- [ ] A `task-updated` event updates that task's status and phase
- [ ] Pressing pause on a running task calls the pause endpoint and shows it as paused
- [ ] Pressing resume on a paused task calls the resume endpoint
- [ ] Cancel asks for confirmation for a running or started paused task and not for a queued one; confirming calls the cancel endpoint
- [ ] Delete always asks for confirmation; confirming calls the delete endpoint and the task leaves the list
- [ ] Controls for disallowed transitions are not offered (e.g. no resume on a queued task)
- [ ] Dragging a task, or using the row menu's move actions, sends the full ordered id list to `PUT /api/queue/order`
- [ ] A rejected reorder restores the server order and shows the error message
- [ ] The queue pause switch calls pause/resume and reflects the queue state
- [ ] When a task finishes, a notice with a link to its transcript (or to History) appears and the row leaves the queue
- [ ] A failed command shows its error on the task's row

## Acceptance criteria
- RTL interaction tests for each control pass against MSW handlers.
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependency.
- Checked against the running backend in item 21.
- User-checked in the browser (carried over from item 24, 2026-10-09): in the cancel and delete confirmation dialogs, Tab and Shift+Tab keep focus inside the dialog and wrap between its buttons, and Escape cancels. jsdom cannot simulate Tab navigation, so item 24's tests cover only focus moving into the dialog and Escape cancelling.
- All test-list items ticked and green; user approved the diff.

## Approval log
- 2026-10-09: consultation answers approved: `@dnd-kit` drag plus row-menu move actions; one batch options panel; language select over `docs/api/languages.json` (extracted from whisper.cpp 1.9.2's C API), default `auto` (item 12 contract changed accordingly); per-file upload progress, one file at a time (XMLHttpRequest in the client); cancel confirmation only for a running or started paused task; delete in the queue and in History, always confirmed; duplicate shown inline with a link to the existing task; a notice with a link when a task finishes. Test list not yet approved.
- 2026-10-09: test list (17 items) approved by the user; status `spec-approved`.
- 2026-10-09: acceptance criterion added at the user's request: the browser check of the confirmation dialog's Tab-key focus cycle, carried over from item 24 because no dialog was rendered in the app before this item.
