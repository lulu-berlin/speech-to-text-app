# 38 — Joined playback stalls at ~90 s of a long clip

Status: in-progress
Depends on: [15](../docs/specs/frontend-history-ui.md) (done; the transcript player's `/audio` join)
Blocks: [35](../docs/specs/readme-and-architecture.md) (its playback GIF)

## Goal

A completed clip plays through its full duration; the player's `currentTime` reaches the end and `ended` fires — no clip is unplayable past a fixed point.

## Already decided

- Found 2026-10-11 by the item 36 workspace agent while running `17-follow-mode.spec.ts` on the user's machine; filed by the root agent after consultation (2026-10-11).
- Evidence: the JFK sample looped 14× (~154 s, `jfk-follow.wav`) freezes at ~90 s (observed `90.204139` and `90.0` across runs), `duration ≈ 154`, `ended` false, never advancing again. The transcript covers the full duration (~9 segments; the last starts at/after the stall point), and the e2e had to become stall-tolerant (an audio-stall guard in `17-follow-mode.spec.ts`).
- Impact: any clip longer than the stall point is unplayable past it; the player silently freezes while the transcript renders.
- Direction to consider (not decided): inspect `FfmpegAudioJoiner` and the chunk concatenation for a chunk whose decoded stream ends early (a short/truncated tail — how the chunker exported it, or how the join's ffmpeg reads the list), and how the served file's duration is derived (the muxed FLAC's header vs what is actually in the file, and how Range requests seek it — ADR 0045's re-encode plays into this).
- May share a root cause with item 37 (both live in the audio-serving path); the consultation decided they are separate items — if a shared cause emerges, this item shrinks to verifying the shared fix.
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion that playback advances past the stall point.

## Proposal

To be settled in the consultation. Backend (or audio-serving) fix; nothing in the frontend's follow mode is implicated — `17-follow-mode.spec.ts` merely became stall-tolerant while the app was broken.

## Open questions (answered 2026-10-11)

- One item or two for the two audio-path findings? → **Two** (user): item 37 is the 500/concurrency; this item is the stall.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [ ] A clip longer than the current stall point plays through to its end: `currentTime` reaches (about) the duration and `ended` fires, in the app
- [ ] Seeking past the former stall point plays there (the file is seekable over its whole range: a Range request near the end returns audio, not a stall)
- [x] The served `/audio` file's duration matches the task's (within a small tolerance); the player does not report a longer duration than the file carries
- [x] A chunk whose decoded stream ends early (a short or truncated tail) joins without stalling the rest of the file
- [ ] (e2e, ADR 0046) Against the real backend: the long clip plays past the former stall point — `17-follow-mode.spec.ts`'s audio-stall guard (or its replacement) no longer has to forgive the stall; playback advances past ~90 s of the 14× clip
- [ ] (must not break) The audio tests and the playback e2e journey stay green

## Acceptance criteria

- The user plays the 14× clip (or any clip longer than the old stall point) to its end: the playhead reaches the duration and playback ends cleanly; seeking past 90 s works.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: filed from the item 36 workspace's findings; consultation: two backend items (item 37 + this one). Test list approved; status `spec-approved`.
- 2026-10-11 (workspace checkpoint, reproduction): on current main the stall does **not** reproduce in the sandbox (real backend, built frontend, store Chromium): the 14× clip plays 0 → 153 s continuously with `duration` 154, seeks to 120/130 decode, the served file's header (154 s) equals its decodable samples (2 464 000), the real `17-follow-mode.spec.ts` is green, and instrumented rounds reach segments at 83/98/116/131 s with the stall guard never firing. A truncated-tail chunk (declares 1 s, holds ~0.29 s) joins, under the current re-encode, into a file whose header equals its content; under the pre-ADR-0045 `-c copy` join the same chunks declare 16 000 samples while decoding 12 608 — the observed item-36 signature (declared ~154 s, stuck at ~90 s, `ended` never fires). Conclusion (confirmed by the user): item 29's ADR 0045 re-encode already fixed this item's bug; the approved list is now a regression-pin suite, backend items first.
- 2026-10-11 (workspace, cycle 1, test-list item 4): green on arrival (regression pin). New `AudioJoinIntegrationSpec` scenario: a task whose first chunk ends early (declared 1 s, bytes cut to 70%, ~4 608 decodable samples) joins with a full 0.5 s tail into one file whose header equals its decodable content (T + 8 000 samples) — both assertions fail under the `-c copy` analog (16 000 vs 12 608). Approved refactor after the cycle: `joinStoredChunks` helper shared by both join scenarios. `gradle ktlintCheck` + `gradle integrationTest --tests stt.http.AudioJoinIntegrationSpec` green; committed `Test:` + `Refactor:` on the branch.
- 2026-10-12 (workspace, cycle 2, test-list item 3): green on arrival (regression pin). New `AudioJoinIntegrationSpec` scenario serving `/api/tasks/{id}/audio` through `testApplication` with the **app's own** `FfmpegAudioJoiner` + `JoinedAudioCache` (no `RecordingJoiner` override — the route's real join-and-serve path, only previously covered with fakes): a COMPLETED paused-queue task with 0.3 s + 0.5 s sine chunks and stored duration 800 ms serves a file whose header declares exactly 12 800 samples (800 ms × 16 kHz) — the timeline the UI shows and the file playback trusts agree, so the player can never report a duration longer than the file carries. Approved refactor after the cycle: `seedCompletedTask` helper extracted. 4/4 integration scenarios green; committed `Test:` + `Refactor:`.