# Audio request 500: the joiner shares the engine's single-flight runner

Status: done (roadmap item 37, merged 2026-10-12)
Depends on: [15 — History UI](frontend-history-ui.md) (done; the transcript player's `/audio` join)
Blocks: [35 — README and architecture overview](readme-and-architecture.md) (its playback GIF opens transcripts while the queue may run)

## Goal

Opening a completed transcript's player answers `200` with a playable file even while the engine processes other tasks — never `500` because the join happened to race a transcription.

## Already decided

- Found 2026-10-11 by the item 36 workspace agent (workspace `workspaces/36-follow-mode-hold-off-race`) while running the Playwright e2e suite on the user's machine; filed by the root agent after consultation (2026-10-11).
- Evidence (backend stdout, every full-suite run at `05-transcript`): `ERROR io.ktor.server.Application - Unhandled exception while serving /api/tasks/1/audio` → `java.lang.IllegalStateException: A run is already in flight: ProcessRunner is single-flight at stt.process.ProcessRunner.run(ProcessRunner.kt:51) ← stt.audio.FfmpegAudioJoiner.join(FfmpegAudioJoiner.kt:47)` (concurrent diarization logs at the same moment).
- Impact: opening a completed clip's transcript while the engine transcribes answers 500 for the audio; the player silently has no audio. The transcript renders, so tests pass — the defect is invisible to assertions.
- Direction to consider (not decided): the joiner's ffmpeg run serves an HTTP request, not an engine task — it does not need the single-flight runner's pause/cancel coupling with the engine (ADR 0020). Give audio joining its own execution path: a second `ProcessRunner` (or a small serial spawner) owned by the joiner. The runner's invariants are about engine process trees; the joiner needs isolation and cleanup, not pause integration.
- [ADR 0046](../decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion that the invisible 500 stops being silent.

## Proposal

To be settled in the consultation (the direction above is a starting point). Either way, the fix is backend-only and confined to the audio-serving path.

## Shared path with item 38 (note for its workspace)

Item 38 investigates a playback stall on the same audio-serving path: `/api/tasks/{id}/audio` → `JoinedAudioCache` → `AudioJoiner`/`ProcessRunner`. This item only changed *who owns the joiner's process execution*: `FfmpegAudioJoiner` now creates a fresh `ProcessRunner` per join and its DI wiring in `SttApp.kt` no longer resolves the engine's runner. The route, the cache, the ffmpeg command and the engine's runner are untouched. 38's workspace should not find a runner-contention cause here; if its stall turns out to sit in the route/cache layer, that is separate and was not changed by this item.

## Open questions (answered 2026-10-11)

- One item or two for the two audio-path findings? → **Two** (user): this item is the 500/concurrency; item 38 is the stall. If a shared root cause emerges, that item shrinks to verifying the shared fix.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [x] Opening a completed clip's transcript while the queue transcribes a task answers 200 for `/audio` and the player has audio (never 500)
- [x] Two simultaneous `/audio` joins both succeed (each join is independent; no serialization errors)
- [x] An engine process and an audio join run concurrently without interference (the engine's pause/cancel keeps working while a join runs)
- [x] (e2e, ADR 0046) Against the real backend: while a task transcribes, a completed transcript's player has a playable file (the currently silent failure must become an assertion)
- [x] (must not break) The audio tests (`AudioJoinCompletedSpec`, the playback e2e journey) and the full suites stay green

## Acceptance criteria

- The user's full-suite run shows no `A run is already in flight` on the `/audio` route; the transcript player has audio while the queue runs.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: filed from the item 36 workspace's findings; consultation: two backend items (this one + item 38), the direction to inspect the joiner's runner usage. Test list approved; status `spec-approved`.- 2026-10-12: merged into `main` (head `b0c70d38`) after the user approved the diff. `FfmpegAudioJoiner` no longer resolves the engine's single-flight `ProcessRunner`: each join runs its own fresh runner, so `/audio` never answers 500 "A run is already in flight" while the engine transcribes, and concurrent joins are independent (ADR 0020's engine runner is untouched). The e2e journey's formerly invisible failure is now the assertion (a completed player reaches readyState ≥ 1 while a background task is RUNNING). Spec and final test list moved here from `roadmap/37-audio-request-500-single-flight-joiner.md`.
