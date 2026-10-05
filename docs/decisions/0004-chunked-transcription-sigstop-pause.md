# 0004 — Chunked transcription with SIGSTOP/SIGCONT pause

Status: accepted · Date: 2026-10-05

## Context
Tasks must be pausable and cancelable, the queue must be pausable, and long files must not lose all progress if the app restarts or crashes. The oracle runs one whisper-cli process over the whole file, which offers no resume point. Snapshotting a running whisper-cli (RAM/VRAM) to disk was considered and rejected: whisper-cli has no checkpoint API, and CRIU cannot checkpoint Vulkan contexts.

## Decision
- Audio is split at silences (ffmpeg `silencedetect`) into chunks of about 5 minutes.
- One whisper-cli run per chunk; the chunk's segments are persisted when the chunk finishes.
- Pause = send `SIGSTOP` to the current chunk's whisper-cli process; resume = `SIGCONT`.
- After an app restart or crash, a task resumes from its last finished chunk.
- Chunk-boundary context loss is mitigated by passing the previous chunk's trailing text to whisper-cli via `--prompt`.
- In-JVM phases (diarization) pause cooperatively through a pause gate checked between embedding windows.

## Consequences
- Progress survives restart: at most one chunk's work is lost.
- Boundary-quality risk: words or sentences cut at chunk boundaries may be transcribed worse than in a single run; silence-based cut points and the `--prompt` tail reduce but do not remove this.
- A paused (stopped) whisper-cli process keeps its RAM and VRAM allocated until it is resumed or killed.
- The backend needs a process runner that can send signals (Java has no signal API) and a chunk plan persisted in the database.
- Rejected alternatives: RAM/VRAM snapshot of whisper-cli (no checkpoint API), CRIU checkpoint/restore (cannot checkpoint Vulkan contexts).
