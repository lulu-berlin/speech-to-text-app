# 06 — Audio decoding and chunking

Status: in-progress
Depends on: [03](../docs/specs/database-schema.md) (done), [05](../docs/specs/process-runner.md) (done)

## Goal

Turn an uploaded audio file into (a) mono 16 kHz PCM for speaker embeddings and (b) a plan of ~5-minute chunks cut at silences, exported as WAV files that whisper-cli can transcribe one at a time, so a task can be paused and resumed chunk by chunk.

## Already decided

- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): split at silences found by ffmpeg `silencedetect` into ~5-minute chunks; resume after restart from the last finished chunk.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): the upload is streamed from the BLOB to a temp file when ffmpeg needs it.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): decoding behavior is ported from `docs/oracle/transcribe.py`.

## Proposal (approved 2026-10-06)

- **Scope.** Item 06 is processing logic over files: it takes the task's source audio file in its work directory and produces the decoded PCM, the duration, the chunk plan, and the chunk WAVs. It has no database dependency: writing the source file from the audio BLOB (`AudioRepository.copyTo`), persisting the plan through `ChunkRepository`, reusing it on resume, and creating and deleting the work directory are the engine's (item 11) (consultation 2026-10-06).
- **Work directory** `$STT_DATA_DIR/work/<task-id>`, outside `$STT_DATA_DIR/tmp`, so item 03's upload-spool cleanup (which fails startup on a directory inside `tmp/`) is untouched. Item 11 deletes it when the task reaches a terminal status and removes the directories of tasks that are no longer pending at startup (consultation 2026-10-06).
- **Port oracle `decode_pcm`** (`docs/oracle/transcribe.py`): `ffmpeg -hide_banner -nostats -loglevel error -i <source> -f f32le -ac 1 -ar 16000 <work-dir PCM file>`, run through the item 05 runner (cancellable, pausable). ffmpeg writes the little-endian float32 mono 16 kHz samples to a file instead of stdout, because the runner is line-oriented (ADR 0020); item 08 reads the file. Failure behavior kept from the oracle: non-zero ffmpeg exit → error carrying ffmpeg's stderr; zero decoded samples → error "no audio decoded" (consultation 2026-10-06).
- **Duration** (`tasks.duration_ms`, stored by item 11) = decoded sample count / 16, i.e. the length of exactly the audio whisper-cli and the speaker model see; no ffprobe (consultation 2026-10-06).
- **ffmpeg output on stderr.** Every ffmpeg run passes `-hide_banner -nostats`, so no `\r`-rewritten progress line reaches the runner, which splits on `\n` only (consultation 2026-10-06).
- **Chunk plan** from `ffmpeg -hide_banner -nostats -i <source> -af silencedetect=noise=-30dB:d=0.5 -f null -` (parse `silence_start` / `silence_end` lines from stderr):
  - target chunk length 5 min, hard cap 6 min, `noise=-30dB`, `d=0.5` — constants, not configuration (consultation 2026-10-06);
  - starting at the current chunk start, cut at the midpoint of the detected silence whose midpoint is nearest to `start + target`, considering only silences that end the chunk within the cap;
  - if no silence lies within the cap, cut at `start + target`;
  - repeat until the end of the audio; a file shorter than the target is one chunk;
  - a trailing chunk shorter than 30 s is merged into the previous chunk when the merged chunk stays at or below the cap; otherwise it stays separate, so the cap remains a hard limit (consultation 2026-10-06);
  - the planner is a pure function `(durationMs, silences, target, cap) → [(startMs, endMs)]`; chunks are contiguous and cover the whole file.
- **Export chunk WAVs** (16 kHz mono 16-bit PCM, `pcm_s16le`: the input format whisper-cli documents) into the work directory, cut from the decoded 16 kHz PCM so boundaries are sample-exact (sample format decided 2026-10-06).

## Open questions (answered 2026-10-06)

- Target and cap lengths and silence thresholds? → **5 min / 6 min, `noise=-30dB`, `d=0.5`**, as constants.
- Should a very short trailing chunk (under 30 s) be merged into the previous one? → **Only if the merged chunk stays within the 6 min cap.**
- Duration source? → **Decoded sample count** (`samples / 16` ms).
- Persisting into `chunks` (table owned by item 03, ADR 0019): this item or item 11? → **Item 11** persists the plan and reuses it on resume; item 06 has no database dependency.
- Temp directory location and deletion? → **`$STT_DATA_DIR/work/<task-id>`**, outside `tmp/`; item 11 deletes it at terminal status and cleans leftovers at startup.
- (added at consultation) The decoded PCM is binary, but the item 05 runner is line-oriented (ADR 0020): how does it get out of ffmpeg? → **ffmpeg writes it to a file** in the work directory; no runner change.
- Should the runner treat `\r` as a line terminator for ffmpeg's `-stats` progress? → **No: ffmpeg runs with `-nostats`**; no runner change.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-06 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Items that run real ffmpeg are tagged `Integration` (ADR 0021).

- [x] A file shorter than the target length becomes exactly one chunk covering the whole file
- [x] Audio with no silences is cut every target length (no silence within the cap)
- [ ] With many silences, each cut lands at the midpoint of the silence nearest the target
- [ ] A silence beyond the cap is never chosen; the cut falls back to the target
- [ ] Planned chunks are contiguous, non-overlapping, and end exactly at the file duration
- [ ] A trailing chunk shorter than 30 s is merged into the previous chunk when the merged chunk stays within the cap; otherwise it stays a separate chunk
- [ ] `silencedetect` stderr lines are parsed into silence intervals (including a trailing silence without `silence_end`)
- [ ] Integration: decoding writes mono 16 kHz float32 samples to the PCM file, and the duration is the sample count / 16 ms, matching the source's known length
- [ ] Integration: decoding a file ffmpeg cannot read fails with ffmpeg's error message
- [ ] Integration: decoding a file with no audio fails with "no audio decoded"
- [ ] Integration: an ffmpeg-generated tone+silence fixture is split at the expected silence midpoints and the exported chunk WAVs have the planned lengths

## Acceptance criteria

- Chunk planner unit tests cover: no silence, many silences, file shorter than target, the short-tail merge rule.
- Integration test (tagged `Integration`) on an ffmpeg-generated tone+silence fixture produces chunk WAVs whose boundaries match the planner output.
- Decode errors surface the oracle's two failure cases (ffmpeg failure, no audio).
- Item 06 has no dependency on the database or Ktor; every ffmpeg run passes `-hide_banner -nostats`, and no change is made to the item 05 runner.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-06: consultation decisions approved: chunk target 5 min / cap 6 min, `silencedetect` `noise=-30dB`, `d=0.5`, as constants; a trailing chunk under 30 s merges into the previous one only within the cap; duration from the decoded sample count; item 11 persists the plan, reuses it on resume, and owns the work directory's lifecycle; work directory `$STT_DATA_DIR/work/<task-id>`; ffmpeg writes the PCM to a file (the runner stays line-oriented); every ffmpeg run passes `-nostats` (the runner keeps splitting on `\n` only). Test list of 11 items approved with the spec. No new ADR: item-local processing decisions, recorded here; the cross-item ones (persistence and work directory in item 11) are also noted in item 11's draft.
- 2026-10-06: chunk WAV sample format decided: 16-bit PCM (`pcm_s16le`), whisper.cpp's documented whisper-cli input (README v1.9.2: "currently runs only with 16-bit WAV files"), over float32 WAV (accepted by v1.9.2's miniaudio loader in code, but undocumented).
