# 0001 — Stack and monorepo layout

Status: accepted · Date: 2026-10-05

## Context
The project is a web app that transcribes uploaded audio files with speaker diarization, using `docs/oracle/transcribe.py` as the behavioral reference. It needs a backend that drives external tools (ffmpeg, whisper-cli) and a speaker-embedding model, a persistent work queue, a browser UI, a reproducible development environment, and a single installable deliverable. The user chose the stack and repository layout up front.

## Decision
- Backend: Kotlin + Ktor, with SQLite as the database.
- Frontend: TypeScript + React + styled-components, built to static files and served by the backend (no separate web server).
- One monorepo in `/home/lulu/speech-to-text-app` holding backend, frontend, Nix expressions, docs, and the roadmap.
- All asynchronous and concurrent work uses Kotlin coroutines (structured concurrency).
- Development dependencies come from devenv (`devenv.nix`); nothing is installed ad hoc.
- The deliverable is a Nix flake package (derivation) that runs backend, embedded database, frontend, and the whisper model together.

## Consequences
- Backend and frontend share one repository, one history, and one review/merge flow (see [AGENTS.md](../../AGENTS.md)).
- Every tool the app or build needs must be available both in `devenv.nix` (development) and in `flake.nix` (packaging).
- SQLite is embedded via the JDBC driver, so no separate database service has to be run or packaged.
- The backend must serve the built frontend, including an SPA fallback to `index.html`.
- Blocking work (JDBC, subprocesses, file I/O) must be confined to `Dispatchers.IO`; raw threads and executors are excluded from app code.
