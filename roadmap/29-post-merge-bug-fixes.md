# 29 — Post-merge bug fixes: engine wake, seek bar, audio join, SSE disconnect

Status: in-progress
Depends on: —

## Goal

Fix the four bugs found in the first real end-to-end runs (user QA, 2026-10-10/11), all sandbox-reproducible, none visible to the unit/integration suites.

## Evidence (each with a reproduction)

1. **An HTTP upload never wakes the engine — the task stays QUEUED until a queue pause/resume.** `ProcessingEngine.wake()` (public; its KDoc names "a new upload") is only ever called by the engine's own command paths and the integration spec (`ProcessingEngineIntegrationSpec` seeds the DB and then calls `resolve<ProcessingEngine>().wake()` manually). `UploadRoutes` inserts the task and never wakes the worker, which suspends on `wakeups.receive()`. Reproduced twice in the sandbox: upload → QUEUED indefinitely; `POST /api/queue/pause` + `resume` → RUNNING → COMPLETED in ~20 s. The user hit it in the browser ("I had to pause and un-pause the queue to unqueue the task").
2. **Seek-bar thumb jumps during playback.** `AudioPlayer` feeds `Slider value={floor(currentTimeMs/1000)}` and seeks on Radix's `onChange` — which fires on every drag tick; each seek sets `audio.currentTime`, which fires `seeked`, which feeds `PLAYHEAD` back into the same value while the user is still dragging, and `timeupdate` keeps firing during a playing drag. The controlled value and the drag fight. Fix: keep the playhead value only while not dragging; seek on release (`onValueCommit`) or via a drag-draft state (ADR 0038 — machine-owned).
3. **`/api/tasks/{id}/audio` 500s for every finished task.** Chunk FLACs are stored in the DB (`ChunkAudioTable.flac`); `FfmpegAudioJoiner` materializes them into scratch files `work/<id>/join-<idx>.flac` **inside the task's work directory** — which item 11's cleanup deletes when the task completes. First scratch write throws `NoSuchFileException` → `{ "error": "internal" }`. Reproduced in the sandbox (`/tmp/stt-big/work/1/join-0.flac`) and in the user's browser (Firefox media 500). The integration spec seeds the work dir, so it never saw the completed-task state.
4. **A disconnecting SSE client throws an unhandled route error.** `EventsRoutes` writes the snapshot and flushes; a client that closes mid-write raises `ClosedByteChannelException` (Ktor 3.6) which escapes the route ("Unhandled exception while serving /api/events"). The pump already handles the overflow case (`FellBehindException`); the writer's channel-closed case must end the stream quietly — a disconnect is ordinary for SSE.

## Proposal (approved 2026-10-11)

- **Fix 1**: `UploadRoutes` resolves the `ProcessingEngine` and calls `wake()` after a successful `createFromUploadOrDuplicate` (mirroring the integration spec). New route test: an HTTP upload is processed to completion **without** any pause/resume or other engine event.
- **Fix 2**: the seek bar seeks on **release** (`onValueCommit`), and the slider's value is the playhead except while the user drags (a drag-draft in the playback/UI machine, ADR 0038). The audio element's own events remain the sole source of truth for the playhead.
- **Fix 3**: the joiner materializes its scratch files in a directory it creates and owns for the join (`Files.createDirectories` on the task work dir, or a scratch dir under the cache `tmp/` — the workspace proposes which at the first checkpoint), and a route-level test joins a **completed** task's audio (no work dir present) and serves it.
- **Fix 4**: `EventsRoutes` catches the writer's `ClosedByteChannelException` (and the pump continues to translate a sequence gap into `FellBehindException`), so a disconnect ends the stream without an unhandled error. Test: a subscriber that closes mid-pump stops the pump quietly.

## Open questions

- None (the workspace proposes the fix-3 scratch location at its first checkpoint, confirmed with the user).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] An upload processed through the HTTP API runs to COMPLETED with no queue toggle or other engine signal (fix 1, red today: stays QUEUED)
- [ ] While the user drags the seek bar, the thumb follows the drag and does not jump back to the playhead; on release the audio seeks to the dragged position and the highlight follows (fix 2)
- [ ] A completed task's `/api/tasks/{id}/audio` serves the joined FLAC (the joiner's scratch survives without the work directory); 200 with `audio/flac` and Range still works (fix 3)
- [ ] Closing the client mid-stream ends the SSE response quietly — no unhandled exception logged, no response error (fix 4)
- [ ] (added 2026-10-11, from item 21's cycle-2 finding, user-confirmed) A silent idle network partition is detected: the backend emits SSE keepalive comment frames while the stream is idle (item 12's route; not a contract change — comments are transparent to EventSource), and the frontend treats N seconds without any event as a dropped stream (reconnecting → first snapshot heals; the connection machine, item 13/17). Today the app shows "Live" against an unreachable server forever.
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test` and `pnpm build` stay green; the existing upload/join/SSE/player tests pass unchanged where their scenario overlaps

## Acceptance criteria

- All four reproductions from the Evidence section are fixed and pinned by the tests above (each test red before, green after).
- The user's browser flow works end to end: upload starts without a queue toggle, the seek bar behaves, transcript audio plays.
- No behavior change outside the four fixes; all suites green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's QA findings (upload needed a queue pause; seek thumb jumping; Firefox media 500; SSE unhandled-exception lines in the app logs). The `FfmpegAudioJoiner` scratch-in-work-directory cause was confirmed from a live 500 (`NoSuchFileException: …/work/1/join-0.flac`). Status `spec-approved`.
- 2026-10-11 (re-consultation): workspace item 21 (e2e) independently confirmed finding 1 with a harness repro on the real backend — upload stays QUEUED for 40+ s with only the initial snapshot on `/api/events`; a queue resume then processes it to COMPLETED in ~2 s — and committed the honest red test as `c5dc432` on its branch (goes green once this item's fix 1 lands on main; item 21 then rebases). Item 21 also surfaced a lower-severity finding, user-confirmed: no SSE keepalives, so an idle silent partition is never detected (the app holds "Live" against an unreachable server). The root agent classified it as not a contract change (SSE comments are transparent to EventSource) and appended it as test-list item 6 (backend heartbeat in item 12's route + frontend inactivity watchdog in the item 13/17 connection machine). All six items approved.