# API contract examples

Example JSON for every response body, SSE event payload and error of the HTTP API. The contract itself is the "Contract" section of [roadmap item 12](../../roadmap/12-http-api-and-sse.md) (moved to `docs/specs/` when the item is done). The rule for these files is [ADR 0029](../decisions/0029-contract-first-frontend-shared-api-examples.md).

## Rules

- The frontend's MSW handlers serve these files and no other mock data (item 13).
- Item 12's route tests check every real response and event payload against the examples of its shape:
  - the same keys;
  - the same JSON value types;
  - `null` only where an example of that shape has `null`.
- The values in the files are illustrative.
- A shape can have several files (`<shape>.<variant>.json`). Together they define the shape: a field is nullable if `null` appears for it in any variant, and its type is the type of its non-null values.
- A contract change edits these files and item 12's contract section in the same commit.

## Shapes

| Shape | Files | Used by |
| ----- | ----- | ------- |
| Task | `task.queued.json`, `task.running.json`, `task.completed.json`, `task.failed.json` | `GET /api/tasks/{id}`; `201` of `POST /api/tasks`; `200` of `POST /api/tasks/{id}/pause\|resume\|cancel\|retry`; event `task-updated` |
| Task list | `task-list.json` | `GET /api/tasks` |
| Queue | `queue.json` | `GET /api/queue`; `200` of `POST /api/queue/pause\|resume` and `PUT /api/queue/order`; event `queue-updated` |
| Snapshot | `snapshot.json` | event `snapshot` (first event of every `/api/events` connection) |
| Task progress | `task-progress.json`, `task-progress.speakers-off.json` | event `task-progress` |
| Task deleted | `task-deleted.json` | event `task-deleted` |
| Turns | `turns.json`, `turns.speakers-off.json` | `GET /api/tasks/{id}/turns` |
| Error | `error.json` | every error response except `duplicate-audio` |
| Error (duplicate) | `error.duplicate-audio.json` | `409 duplicate-audio` of `POST /api/tasks` |

Responses without a body (`204` of `DELETE /api/tasks/{id}`), the transcript downloads (item 10's formats) and `/audio` (FLAC) have no example here.

## Task transition table

`task-transitions.json` is contract data, not a response. It lists:
- `transitions`: the allowed status transitions per status (item 04, amended by item 11 for retry);
- `commands`: for each task command endpoint, the statuses it accepts and the status it requests.

Delete is not a transition and works on any task.

- The frontend's state machine reads this table, so the UI only offers commands the task's status allows (item 13).
- A backend test checks that `TaskStatus` has exactly these transitions (item 11).
