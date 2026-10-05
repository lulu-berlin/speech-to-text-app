# 0005 — Uploaded audio stored as SQLite BLOB

Status: accepted · Date: 2026-10-05

## Context
Uploaded audio must be kept for processing (including resume after restart, ADR 0004) and for history playback. It can live either in the database or as files in a data directory referenced from the database.

## Decision
Uploaded audio is stored as a **SQLite BLOB**. When ffmpeg or whisper-cli need the audio, it is streamed from the BLOB to a temporary file.

## Consequences
- The database is the single source of truth: one file to back up; no orphaned or missing audio files.
- Database size grows with uploaded audio; whether to keep audio after completion is an open question for the schema item.
- Uploads and reads must stream the BLOB rather than load it fully into memory.
- Task listings must never load BLOBs (audio is kept out of the main task rows).
- Temporary files must be created per task and cleaned up after use.
