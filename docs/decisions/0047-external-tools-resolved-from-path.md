# 0047 — External tools are resolved from PATH; no tool-path variables

Status: accepted · Date: 2026-10-11

## Context
The backend runs three external programs by name: `whisper-cli` (`ChunkTranscriber`, item 07), `ffmpeg` (`FFMPEG`, item 06) and `setsid` (`ProcessRunner`, ADR 0020). In development the devenv puts them on `PATH`. Roadmap item 16's proposal named two configuration variables, `STT_WHISPER_CLI` and `STT_FFMPEG`, for the packaged wrapper to set, but `Config.fromEnv()` never read either of them: setting them would have been dead configuration. Item 33's consultation (2026-10-11) had already chosen `PATH` for the Gradle bootstrap ("no new config, no backend change").

## Decision
- The backend resolves `whisper-cli`, `ffmpeg` and `setsid` from `PATH`. There are no `STT_*` variables for tool paths.
- `STT_WHISPER_CLI` and `STT_FFMPEG` are withdrawn (deprecated before they were ever implemented): the flake's wrapper does not set them, and no item adds them without a new ADR.
- Each delivery puts its own tools first on `PATH`: the flake's `bin/stt-app` uses `makeWrapper --prefix PATH` with the pinned whisper-cpp (1.8.4, Vulkan), `ffmpeg`, `util-linux` (`setsid`) and `coreutils` (item 16); the Gradle bootstrap prepends its managed tools' `bin/` for the spawned backend (item 33); the devenv provides them in the shell.

## Consequences
- One resolution mechanism in every environment; the backend has no tool-path configuration to validate.
- The wrapper prefixes rather than replaces `PATH`: a caller's tools (for example the devenv's own `whisper-cli`) cannot shadow the packaged ones, and other programs on the caller's `PATH` stay reachable.
- Nothing checks the tools at startup: a missing tool shows only when a task first runs it. The packaging items verify their wrappers' tools instead (item 16's test list).
