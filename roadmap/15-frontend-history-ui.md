# 15 — Frontend history UI

Status: in-progress
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](../docs/specs/design-system.md) (done)

## Goal
Let the user browse past transcriptions: find a finished task, read its transcript with speakers and timestamps, follow it during playback with the played segment highlighted, jump around by clicking, download it in every format, listen to the task's audio, see why a task failed or was cancelled, and retry or delete it.

## Already decided
- [Item 24 design system](../docs/specs/design-system.md) ([ADR 0033](../docs/decisions/0033-design-system-tokens-radix-plex.md)): the History and transcript views are built with its tokens and primitives (Card, StatusBadge, Button, ConfirmDialog, Slider for the seek bar, Page/Stack/Row, EmptyState, Skeleton); item 25 does the final visual pass (History as cards, the transcript as a centred reading column).
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0024 — Audio storage](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): `GET /api/tasks/{id}/audio` serves one FLAC joined from the stored chunks (16 kHz mono, not the original upload); a task cancelled or failed before chunking has none.
- [ADR 0029](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md), [ADR 0031](../docs/decisions/0031-frontend-routing-react-router-state-xstate.md): built against item 12's contract with MSW mocks; routes `/history` and `/history/:taskId`; commands through item 13's command machine.
- Item 12's contract: `GET /api/tasks/{id}/turns` returns the task's speakers (`speakerId`, stored name `speaker`) and item 09's turns referencing them by `speakerId` (ADR 0028); turn and segment times are on the `/audio` timeline; `/audio` supports Range; Task carries `phase`, `transcribedUntilMs`, `error` and `hasAudio`; `DELETE /api/tasks/{id}`; `task-deleted` removes a task; `/turns` and downloads answer `409 not-completed` unless COMPLETED.
- FAILED and CANCELED tasks can be retried (`POST /api/tasks/{id}/retry`, item 11).
- Decided in the item 14 consultation: **delete is offered in History too, always with confirmation**; it frees the file's `sha256`, so the same file can be uploaded again.

## Proposal (approved 2026-10-09)
- **History list** of finished tasks (COMPLETED, FAILED, CANCELED), newest first, with filename, status, date and duration; FAILED tasks show their error. **A filename search box and a status filter** (all / completed / failed / canceled), both client-side over the task list the app already holds (decided 2026-10-09; no backend search). The list updates live: a task that finishes appears, a retried one leaves, a deleted one disappears.
- **Transcript view** for a COMPLETED task (`/history/:taskId`):
  - speaker turns with timestamps from `/turns`; each speaker's name shown with **a subtle per-speaker tint** from a speaker palette (tokens added to item 24, light and dark, AA-checked; speaker N uses palette entry N mod palette size); speakers-off transcripts show no labels;
  - download buttons for the three formats: txt, srt and vtt (`GET /api/tasks/{id}/transcript?format=...`); json was dropped ([ADR 0035](../docs/decisions/0035-no-json-download.md));
  - the audio player sourced from `/audio`, with a seek bar (item 24's Slider);
  - **playback highlight**: while the audio plays, the segment whose `[startMs, endMs)` contains the current time is highlighted inside its turn; nothing in a gap between segments; it follows seeking and pausing;
  - **click-to-seek**: clicking a segment (or a turn's timestamp) seeks the player to its start **and plays** (decided 2026-10-09);
  - **follow mode**: the highlighted segment is kept in view while playing; scrolling by hand stops following and shows a "Back to playing position" button that resumes it (decided 2026-10-09).
- **Status page for a FAILED or CANCELED task** (same route; decided 2026-10-09): what happened, e.g. "Failed while transcribing at 12:34" (from `phase` and `transcribedUntilMs`) or "Cancelled while chunking", the error message, **Retry** and **Delete**, and the audio player if `hasAudio`. No transcript.
- **No audio**: when `hasAudio` is false the player is not rendered; a short note says the task has no audio because it ended before chunking.
- **Retry** on FAILED and CANCELED tasks (in the list and on the status page); the retried task leaves History and appears in the queue (item 14) via `task-updated`.
- **Delete** on every finished task (in the list and the detail view), always confirmed.
- A missing or deleted task (`404`, or deleted while open) shows a not-found message.
- Long transcripts: the highlight lookup is a binary search over the segments' start times on each player `timeupdate`, not a scan.

## Open questions (answered 2026-10-09)
- Search/filter? → **Filename search plus status filter, client-side.**
- Click-to-seek? → **Seeks there and plays.**
- Follow playback? → **Follow until the user scrolls**, with a "Back to playing position" button.
- Task without audio? → **New Task field `hasAudio`** (item 12 contract change); the player is hidden with a note.
- FAILED/CANCELED detail? → **A status page** with what happened, the error, Retry, Delete and the audio if stored.
- Speakers? → **Name plus a per-speaker tint** (a speaker palette added to item 24's tokens).

## Remaining open questions
None.

## Test list (written with the user; Canon TDD step 1)
Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.
- [x] The history list shows completed, failed and canceled tasks, newest first, and no pending ones
- [x] The filename search and the status filter narrow the list
- [x] A failed task shows its error message in the list
- [x] Opening a completed task shows its speaker turns with timestamps and each speaker's name with its own tint
- [x] A speakers-off transcript is shown without speaker labels
- [ ] Each download button links to the transcript in its format
- [ ] The audio player points at the task's audio endpoint; with `hasAudio` false no player is rendered and a note explains why
- [ ] While the audio plays, the segment containing the current time is highlighted; in a gap between segments none is; seeking moves the highlight
- [ ] Clicking a segment seeks the player to its start and plays
- [ ] While playing, the highlighted segment is kept in view; scrolling by hand stops following and "Back to playing position" resumes it
- [ ] A failed or canceled task opens a status page with what happened (phase and `transcribedUntilMs`), the error, Retry and Delete, and no transcript
- [ ] Retry is offered on failed and canceled tasks only and calls the retry endpoint; the retried task leaves the history list
- [ ] Delete always asks for confirmation; confirming calls the delete endpoint and the task leaves the list
- [ ] A task finishing while the history view is open appears in the list
- [ ] A missing task (404) shows a not-found message

## Acceptance criteria
- RTL tests pass against MSW handlers.
- The speaker palette meets AA contrast in both themes.
- `pnpm test` and `pnpm build` are green.
- Checked against the running backend in item 21.
- All test-list items ticked and green; user approved the diff.

## Approval log
- 2026-10-09: consultation answers approved: client-side filename search and status filter; click-to-seek seeks and plays; follow mode until the user scrolls; new Task field `hasAudio` (item 12 contract and `docs/api/` updated); a status page for failed/canceled tasks; speakers shown with a per-speaker tint (a speaker palette added to item 24); delete in History, always confirmed (item 14 consultation). Test list not yet approved.
- 2026-10-09: test list (15 items) approved by the user; status `spec-approved`.
- 2026-10-09: item 10 consultation: the transcript downloads are txt, srt and vtt only ([ADR 0035](../docs/decisions/0035-no-json-download.md)); their speaker labels are the stored names ([ADR 0034](../docs/decisions/0034-speaker-labels-are-stored-names.md)), so a renamed speaker shows in the downloads without any work here.
