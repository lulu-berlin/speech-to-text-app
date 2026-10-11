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
- [ ] The served `/audio` file's duration matches the task's (within a small tolerance); the player does not report a longer duration than the file carries
- [ ] A chunk whose decoded stream ends early (a short or truncated tail) joins without stalling the rest of the file
- [ ] (e2e, ADR 0046) Against the real backend: the long clip plays past the former stall point — `17-follow-mode.spec.ts`'s audio-stall guard (or its replacement) no longer has to forgive the stall; playback advances past ~90 s of the 14× clip
- [ ] (must not break) The audio tests and the playback e2e journey stay green

## Acceptance criteria

- The user plays the 14× clip (or any clip longer than the old stall point) to its end: the playhead reaches the duration and playback ends cleanly; seeking past 90 s works.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: filed from the item 36 workspace's findings; consultation: two backend items (item 37 + this one). Test list approved; status `spec-approved`.