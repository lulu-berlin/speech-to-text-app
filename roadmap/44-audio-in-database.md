# 44 — Store the full audio in the database; drop the file cache

Status: draft
Depends on: [11](../docs/specs/processing-engine.md) (done), [12](../docs/specs/http-api-and-sse.md) (done), [38](38-joined-playback-stalls.md) (the stall fix — the migration concatenates chunk FLACs and playback serves the canonical file)
Blocks: —

## Goal

No file cache and no per-task work directories for audio: the whole decoded clip lives in the SQLite database in one canonical format, chunks are just `(fromMs, toMs)` pointers into it, and transcription materializes a small temporary input per chunk that is deleted as soon as it has been processed. The data directory becomes the database alone.

## Already decided

- User request (2026-10-12): "get rid of the cache folder and instead store everything in the sqlite database… store the full audio clip in the right format and frequency and then the chunks are just pointers (fromMs, toMs) and whenever we want to transcribe we can very cheaply create a temporary file that can be immediately deleted after it's processed."
- Today (verified 2026-10-12): per-chunk FLAC blobs already live in the database (`chunk_audio`, V2); `$STT_DATA_DIR/tmp` holds the upload spools and the joined-playback cache (`tmp/<id>.flac`, item 12/15); `$STT_DATA_DIR/work/<id>` holds the uploaded original until chunking and the processing scratch (decoded PCM, whisper input copies, join scratch, ADR 0024). The canonical decode is 16 kHz mono — the oracle's and the chunker's format.
- [ADR 0024](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md) is the decision this item replaces; the data directory is the backup unit, and after this item that unit is one file.
- [ADR 0045](../docs/decisions/0045-joined-playback-flac-reencoded.md): the playback join re-encodes so it is seekable — a canonical FLAC is seekable as-is, so serving it directly satisfies the intent.

## Proposal

To be settled in the consultation. Starting point (what the user described):

- One canonical audio blob per task in the database, at 16 kHz mono in a single format (open question: FLAC — compact, seekable frame-wise — vs raw float PCM — simplest, ~230 MB/hour — vs a documented hybrid). Uploads spool directly into the blob (the streaming-window path already exists; the spool file disappears).
- Chunks become `(fromMs, toMs)` pointers into the canonical blob; the per-chunk FLAC blobs are gone. Transcription extracts the window to a small temporary file (FLAC or WAV), runs whisper-cli, and deletes the file before the next chunk.
- Playback serves the canonical blob directly (a 16 kHz mono FLAC plays in browsers and is seekable; the joined-cache, its ffmpeg run and `tmp/` all go away). Diarization reads the same blob (item 43's rebuild problem largely dissolves).
- What remains on disk: only ephemeral whisper-cli input files (whisper needs a path), created per chunk and deleted — under `tmp/` or the OS temp dir (open question).
- Migration: existing tasks' chunk FLACs are concatenated into the canonical blob once (this is exactly the failure item 38 works on — this item depends on 38's correctness), and the legacy columns/tables are dropped or kept read-only (open question).

## Open questions (to be answered in the consultation)

- The canonical format and frequency storage: one FLAC (compact, seekable) vs raw float PCM (no encode cost, large) vs another choice — and how windows are extracted (decode the whole blob vs frame-seek into it).
- Playback: serve the canonical FLAC directly (likely also fixes item 38's stall class at its root), or keep a derived serve format?
- Ephemeral whisper input files: `tmp/` (still a directory, never a cache) or the OS temp dir?
- Migration of existing tasks: rebuild the canonical blob from the stored chunk FLACs, or keep the legacy chunk path readable for old tasks while new ones use the new scheme?

## Test list (proposed; behaviors only)

- [ ] A fresh upload stores the full decoded clip in the database; after the task completes, no audio file exists anywhere on disk (neither `work/` nor `tmp/`)
- [ ] Restarting mid-queue keeps everything working with no work directories: queued, running and completed tasks behave as before and finish (or resume) correctly
- [ ] Transcription creates one temporary input per chunk and deletes it immediately after processing — no leftovers after a task
- [ ] Playback serves the stored audio directly: seekable over its whole range, full duration, no cache file created anywhere
- [ ] Diarization reads the stored audio without the old full reconstruction path
- [ ] Item 38's long clip plays past the former stall point through the new path
- [ ] Backups are the database file alone; restoring it restores the full history and playback
- [ ] The upload spool never hits disk (a large upload leaves no temporary file on success, failure or cancel)
- [ ] Migration: an existing database upgrades and its finished tasks still play and transcribe correctly
- [ ] (must not break) The full suites, the contract-shape checks and the e2e journey stay green

## Acceptance criteria

- `$STT_DATA_DIR` holds only the database; no cache, no per-task work directories; chunks are offsets into one stored clip; transcription's temp inputs are created and deleted per chunk.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: drafted from the user's request (full audio in SQLite, chunk pointers, transient per-chunk temp inputs).