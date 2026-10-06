# 06 — Audio decoding and chunking

Status: draft
Depends on: [03](../docs/specs/database-schema.md) (done), [05](05-process-runner.md)

## Goal

Turn an uploaded audio file into (a) mono 16 kHz PCM for speaker embeddings and (b) a plan of ~5-minute chunks cut at silences, exported as WAV files that whisper-cli can transcribe one at a time, so a task can be paused and resumed chunk by chunk.

## Already decided

- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): split at silences found by ffmpeg `silencedetect` into ~5-minute chunks; resume after restart from the last finished chunk.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): the upload is streamed from the BLOB to a temp file when ffmpeg needs it.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): decoding behavior is ported from `docs/oracle/transcribe.py`.

## Proposal (pending approval)

- **Port oracle `decode_pcm`** (`docs/oracle/transcribe.py`): run `ffmpeg -hide_banner -loglevel error -i <audio> -f f32le -ac 1 -ar 16000 -` and read the output as little-endian float32 samples (mono, 16 kHz). Failure behavior to keep: non-zero ffmpeg exit → error carrying ffmpeg's stderr; zero decoded samples → error "no audio decoded". The oracle holds the whole decode in memory; here the PCM is written to a file in the task temp dir (read back by item 08) instead of being kept on the heap. The process runs through the item 05 runner, so it is cancellable.
- **Probe duration** of the source (stored as `tasks.duration_ms`), e.g. via the decoded sample count (`samples / 16`) or ffprobe.
- **Chunk plan** from `ffmpeg -i <audio> -af silencedetect=noise=-30dB:d=0.5 -f null -` (parse `silence_start` / `silence_end` lines from stderr):
  - target chunk length 5 min, hard cap 6 min;
  - starting at the current chunk start, cut at the midpoint of the detected silence whose midpoint is nearest to `start + target`, considering only silences that end the chunk within the cap;
  - if no silence lies within the cap, cut at `start + target`;
  - repeat until the end of the audio; a file shorter than the target is one chunk;
  - the planner is a pure function `(durationMs, silences, target, cap) → [(startMs, endMs)]`, chunks are contiguous and cover the whole file.
- **Export chunk WAVs** (16 kHz mono, the input format whisper-cli expects) into a per-task temp directory, cut from the decoded 16 kHz stream so boundaries are sample-exact.
- **Persist the chunk plan** in the `chunks` table (`task_id, idx, start_ms, end_ms, status = PENDING`); on resume the existing plan is reused, not recomputed.

## Open questions for the user

- Target and cap lengths (proposed 5 min / 6 min) and silence thresholds (proposed `noise=-30dB`, `d=0.5`)?
- Should a very short trailing chunk (e.g. < 30 s) be merged into the previous one?
- Duration source: decoded sample count or ffprobe?
- ~~Persisting into `chunks` needs the table from item 03: add 03 as a dependency, or leave persistence to item 11?~~ Resolved by [ADR 0019](../docs/decisions/0019-schema-ownership-and-evolution.md): item 03 owns the `chunks` table and `ChunkRepository`; 03 is now in `Depends on`. Still open: does this item persist the plan itself, or hand it to item 11?
- Temp directory location (under `$STT_DATA_DIR/tmp/<task-id>` proposed) and when it is deleted (after completion / cancel). Constraint from item 03 ([spec](../docs/specs/database-schema.md), ADR 0017): `$STT_DATA_DIR/tmp` holds upload spool files; `Database.open` deletes every file directly in it and fails startup (naming `STT_DATA_DIR`) when it finds a non-empty directory there. A per-task directory under `tmp/` left behind by a crash would therefore stop the app from starting: use another location (e.g. `$STT_DATA_DIR/work/<task-id>`) or change item 03's cleanup through a migration-style follow-up.
- Should the process runner (item 05, ADR 0020) treat `\r` as a line terminator? It splits on `\n` only; ffmpeg's progress output (`-stats`) rewrites one line with `\r`, which then arrives as one ever-growing line, and only when ffmpeg finally writes `\n` or exits. Options: split on `\r` too in the runner, pass `-nostats` to ffmpeg, or leave as is.

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] A file shorter than the target length becomes exactly one chunk covering the whole file
- [ ] Audio with no silences is cut every target length (no silence within the cap)
- [ ] With many silences, each cut lands at the midpoint of the silence nearest the target
- [ ] A silence beyond the cap is never chosen; the cut falls back to the target
- [ ] Planned chunks are contiguous, non-overlapping, and end exactly at the file duration
- [ ] `silencedetect` stderr lines are parsed into silence intervals (including a trailing silence without `silence_end`)
- [ ] Decoding produces mono 16 kHz float32 samples whose count matches the source duration
- [ ] Decoding a file ffmpeg cannot read fails with ffmpeg's error message
- [ ] Decoding a file with no audio fails with "no audio decoded"
- [ ] Integration: an ffmpeg-generated tone+silence fixture is split at the expected silence midpoints and the exported chunk WAVs have the planned lengths

## Acceptance criteria

- Chunk planner unit tests cover: no silence, many silences, file shorter than target.
- Integration test (tagged `Integration`) on an ffmpeg-generated tone+silence fixture produces chunk WAVs whose boundaries match the planner output.
- Decode errors surface the oracle's two failure cases (ffmpeg failure, no audio).
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
