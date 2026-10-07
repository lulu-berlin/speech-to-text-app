# 07 — Whisper transcription

Status: in-progress
Depends on: [03](../docs/specs/database-schema.md) (done), [06](../docs/specs/audio-decoding-and-chunking.md) (done)

## Goal

Transcribe each planned chunk with one whisper-cli run, turn its JSON output into segments with absolute timestamps, report progress while it runs, and persist each finished chunk atomically so a paused, cancelled or crashed task can resume from the last finished chunk.

## Already decided

- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): one whisper-cli run per chunk; segments persisted per chunk; previous chunk's trailing text passed via `--prompt`.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): `large-v3-turbo-q5_0`, provided via `STT_WHISPER_MODEL`.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): command construction and JSON parsing are ported from `docs/oracle/transcribe.py`.
- [ADR 0024 — Audio storage: original kept only until chunking, chunks stored as FLAC](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): chunks are stored as FLAC (16-bit, 16 kHz mono); item 11 writes each chunk's FLAC from the database to a file for whisper-cli, which reads FLAC (`whisper-cli --help` 1.9.2: `supported audio formats: flac, mp3, ogg, wav`).

## Proposal (approved 2026-10-07)

- **Command per chunk:** `whisper-cli -m $STT_WHISPER_MODEL -f <chunk>.flac -l <lang> -t <threads> <device args> -oj -of <prefix> -pp [--prompt <prev tail>]`, run through the item 05 runner (cancellable, pausable with `SIGSTOP`/`SIGCONT`). As in oracle `transcribe_one`, `-t` is only passed when a thread count is configured (`STT_THREADS`); otherwise whisper-cli's default applies. `-oj` writes `<prefix>.json`; `-pp` prints progress to stderr. All flags verified against `whisper-cli --help` (1.9.2): `-oj`, `-of FNAME`, `-pp`, `--prompt PROMPT` (max n_text_ctx/2 tokens), `-ng/--no-gpu`, `-dev/--device N`.
- **Port oracle `device_args`** (`docs/oracle/transcribe.py`), input parsed like oracle `parse_device` from `STT_DEVICE`:
  - `auto` → no flag (whisper-cli picks the first GPU);
  - `cpu` → `--no-gpu`;
  - `gpu` → `--device 0`;
  - integer `N` (≥ 0) → `--device N`;
  - anything else (or a negative index) → configuration error.
- **Port oracle `parse_segments`**, both JSON shapes:
  - whisper.cpp ≥ 1.7: top-level `transcription` array; each element's `offsets.from` / `offsets.to` are milliseconds (missing → 0), `text` (missing → empty string);
  - older: top-level `segments` array with `start` / `end` in seconds (the oracle returns it unchanged); converted to ms here;
  - if `segments` is present it takes precedence; neither key present → no segments.
  - Text is stored as whisper-cli wrote it (leading space included); trimming happens when outputs are rendered (item 10), as in the oracle.
- **Offset arithmetic:** stored `start_ms` / `end_ms` = chunk `start_ms` + segment offset, so segments carry absolute positions in the original file; `seq` orders segments within a chunk.
- **Progress:** parse `-pp` progress lines from stderr into a chunk fraction; task progress = (sum of DONE chunk durations + current chunk duration × chunk fraction) / total duration, i.e. weighted by chunk duration. The exact line format is pinned from real whisper-cli 1.9.2 output captured as a test fixture.
- **Prompt:** the trailing ~200 characters of the previous chunk's text, cut at a word boundary (a constant, well under whisper's n_text_ctx/2 token limit), are passed as `--prompt`; the first chunk has no prompt; on resume the tail is rebuilt from the stored segments (consultation 2026-10-07).
- **Persistence:** the chunk's segments are inserted and the chunk is marked `DONE` in one transaction (a new repository operation in `stt/db`, added by this item); a chunk interrupted mid-run leaves no segments behind and is re-run from scratch.
- **Temporary files:** right after that transaction commits, the chunk's FLAC file (written from the database by item 11) and whisper-cli's output JSON are deleted, so the work directory holds at most one chunk's files during transcription (consultation 2026-10-07).
- **Failures** (mirroring oracle `transcribe_one`): non-zero whisper-cli exit → task error with the exit code and stderr tail; JSON output missing → error; JSON not parseable → error. A chunk with no segments is valid (no speech) and is marked `DONE` with zero segments.
- **Test fixtures** (fetched by URL + sha256 through Nix, never committed, per the backend conventions): the `ggml-tiny.en` model, added to `nix/models.nix` and exported by `devenv.nix`, and whisper.cpp's `samples/jfk.wav` (11 s, JFK inaugural address excerpt, a US-government work in the public domain) (consultation 2026-10-07).

## Open questions (answered 2026-10-07)

- Prompt tail length? → **The last ~200 characters of the previous chunk's text, cut at a word boundary.**
- Fixture model for integration tests? → **`ggml-tiny.en`, fetched by hash via `nix/models.nix`.**
- Fixture speech clip? → **whisper.cpp's `samples/jfk.wav`, fetched by hash** (public domain).
- ~~Persisting segments and chunk status needs item 03's tables: add 03 as a dependency?~~ Resolved by [ADR 0019](../docs/decisions/0019-schema-ownership-and-evolution.md): item 03 owns `chunks`/`segments` and their repositories; 03 is now in `Depends on`. Note for the one-transaction persistence in the Proposal: item 03's repositories each run their own transaction (`ChunkRepository.insertAll`, `SegmentRepository.insertAll`) and offer no "mark chunk DONE + insert its segments" operation yet; this item adds it.
- Delete whisper-cli's output JSON and the chunk FLAC right after the chunk is persisted? → **Yes, right after the transaction commits.**

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-07 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Items that run real whisper-cli are tagged `Integration` (ADR 0021).

- [x] JSON with a `transcription` array yields segments with millisecond offsets and text
- [ ] JSON with a legacy `segments` array (seconds) yields the same segments in milliseconds
- [ ] JSON with neither key, or an empty array, yields no segments
- [ ] Missing `offsets` fields default to 0 and missing `text` to an empty string
- [ ] Segment times are shifted by the chunk start offset
- [ ] A `-pp` progress line is parsed into a chunk fraction; unrelated stderr lines are ignored
- [ ] Task progress is weighted by chunk duration across finished and running chunks
- [ ] Device `auto` adds no flag, `cpu` adds `--no-gpu`, `gpu` adds `--device 0`, `3` adds `--device 3`; invalid values are rejected
- [ ] The command includes `-t` only when threads are configured and `--prompt` only when a previous tail exists
- [ ] The prompt is the previous chunk's last ~200 characters, cut at a word boundary; on resume it is rebuilt from the stored segments
- [ ] A non-zero whisper-cli exit fails the chunk with the exit code
- [ ] Missing or malformed JSON output fails the chunk with a clear message
- [ ] A chunk's segments and its `DONE` status are stored together or not at all
- [ ] After the chunk is persisted, its FLAC file and whisper-cli's JSON are deleted
- [ ] Integration: `jfk.wav` transcribed with `ggml-tiny.en` yields non-empty text with increasing timestamps

## Acceptance criteria

- Both whisper-cli JSON shapes and the empty case parse as described above.
- Command construction matches the oracle's `device_args` mapping and the flag list above.
- Tagged `Integration` test transcribes a short speech clip with the fixture model.
- An interrupted chunk leaves no partial segments in the database.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-07: consultation decisions approved: prompt = the previous chunk's last ~200 characters, cut at a word boundary; integration tests use `ggml-tiny.en` and whisper.cpp's `samples/jfk.wav` (public domain), both fetched by hash through Nix; the chunk FLAC and whisper-cli's JSON are deleted right after the chunk's transaction commits. Test list of 15 items approved with the spec. No new ADR: item-local decisions, recorded here.
