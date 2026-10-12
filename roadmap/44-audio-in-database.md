# 44 — Store the full audio in the database; drop the file cache

Status: spec-approved
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

## Open questions (answered 2026-10-12)

- The canonical format? → **One FLAC, 16 kHz mono** (user). No blocking issue found: FLAC is compact (~30–60 MB/hour of speech), lossless, frame-seekable (extraction decodes from the frame before `fromMs` and trims — sub-second margin at 16 kHz), and fast to encode/decode both ways. Two notes recorded: SQLite's default max BLOB is ~1 GB (a 2 h FLAC is ~60–120 MB; item 41's upload size limit bounds recordings anyway), and the blob belongs inside the database the app already backs up.
- Playback? → **Serve the canonical FLAC directly** (user) — modern browsers play FLAC in `<audio>`, the existing `/audio` Range handling applies unchanged, and ADR 0045's re-encode becomes unnecessary (a single clean encode is seekable; the concat defect it fixed cannot occur).
- Ephemeral whisper inputs? → **The OS temp dir** (`java.io.tmpdir`; `/tmp` in the container — writable) with **guaranteed deletion even when the run fails** (user): the per-window temp file is created, used and deleted on every path, success and failure alike.
- Migration? → **Rebuild the canonical blob from the stored chunk FLACs** (user). The schema version is already stored (`flyway_schema_history`, today `version 7`; ADR 0019: new versioned migrations only), so the migration is an ordinary `V8`; forward-only evolution stays the rule (old DB + new app migrates up; the reverse direction is out of scope unless separately decided).

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
- 2026-10-12: consultation answers: canonical FLAC (16 kHz mono) with the blob-ceiling and frame-seek notes; playback serves the stored FLAC directly; whisper inputs in the OS temp dir with cleanup on failure; migration via a versioned Flyway migration (schema versions already stored). Test list approved; status `spec-approved`.
