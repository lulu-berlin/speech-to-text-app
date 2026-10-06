# 07 — Whisper transcription

Status: draft
Depends on: [03](../docs/specs/database-schema.md) (done), [06](06-audio-decoding-and-chunking.md)

## Goal

Transcribe each planned chunk with one whisper-cli run, turn its JSON output into segments with absolute timestamps, report progress while it runs, and persist each finished chunk atomically so a paused, cancelled or crashed task can resume from the last finished chunk.

## Already decided

- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): one whisper-cli run per chunk; segments persisted per chunk; previous chunk's trailing text passed via `--prompt`.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): `large-v3-turbo-q5_0`, provided via `STT_WHISPER_MODEL`.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): command construction and JSON parsing are ported from `docs/oracle/transcribe.py`.

## Proposal (pending approval)

- **Command per chunk:** `whisper-cli -m $STT_WHISPER_MODEL -f chunk.wav -l <lang> -t <threads> <device args> -oj -of <prefix> -pp [--prompt <prev tail>]`, run through the item 05 runner (cancellable, pausable with `SIGSTOP`/`SIGCONT`). As in oracle `transcribe_one`, `-t` is only passed when a thread count is configured (`STT_THREADS`); otherwise whisper-cli's default applies. `-oj` writes `<prefix>.json`; `-pp` prints progress to stderr. All flags verified against `whisper-cli --help` (1.9.2): `-oj`, `-of FNAME`, `-pp`, `--prompt PROMPT` (max n_text_ctx/2 tokens), `-ng/--no-gpu`, `-dev/--device N`.
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
- **Prompt:** the trailing text of the previous chunk (by default its last N characters/words, see open question) is passed as `--prompt`; the first chunk has no prompt; on resume the tail is rebuilt from the stored segments.
- **Persistence:** the chunk's segments are inserted and the chunk is marked `DONE` in one transaction; a chunk interrupted mid-run leaves no segments behind and is re-run from scratch.
- **Failures** (mirroring oracle `transcribe_one`): non-zero whisper-cli exit → task error with the exit code and stderr tail; JSON output missing → error; JSON not parseable → error. A chunk with no segments is valid (no speech) and is marked `DONE` with zero segments.

## Open questions for the user

- Prompt tail length (proposed: last ~200 characters of the previous chunk's text, cut at a word boundary)?
- Fixture model for integration tests: `ggml-tiny.en` fetched via `nix/models.nix` (proposed)?
- Fixture speech clip: source and license (must be redistributable in the repo, or fetched by hash)?
- ~~Persisting segments and chunk status needs item 03's tables: add 03 as a dependency?~~ Resolved by [ADR 0019](../docs/decisions/0019-schema-ownership-and-evolution.md): item 03 owns `chunks`/`segments` and their repositories; 03 is now in `Depends on`. Note for the one-transaction persistence in the Proposal: item 03's repositories each run their own transaction (`ChunkRepository.insertAll`, `SegmentRepository.insertAll`) and offer no "mark chunk DONE + insert its segments" operation yet; this item adds it.
- Should whisper-cli's output JSON and the chunk WAV be deleted right after the chunk is persisted?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] JSON with a `transcription` array yields segments with millisecond offsets and text
- [ ] JSON with a legacy `segments` array (seconds) yields the same segments in milliseconds
- [ ] JSON with neither key, or an empty array, yields no segments
- [ ] Missing `offsets` fields default to 0 and missing `text` to an empty string
- [ ] Segment times are shifted by the chunk start offset
- [ ] A `-pp` progress line is parsed into a chunk fraction; unrelated stderr lines are ignored
- [ ] Task progress is weighted by chunk duration across finished and running chunks
- [ ] Device `auto` adds no flag, `cpu` adds `--no-gpu`, `gpu` adds `--device 0`, `3` adds `--device 3`; invalid values are rejected
- [ ] The command includes `-t` only when threads are configured and `--prompt` only when a previous tail exists
- [ ] A non-zero whisper-cli exit fails the chunk with the exit code
- [ ] Missing or malformed JSON output fails the chunk with a clear message
- [ ] A chunk's segments and its `DONE` status are stored together or not at all
- [ ] Integration: a short speech clip transcribed with the fixture model yields non-empty text with increasing timestamps

## Acceptance criteria

- Both whisper-cli JSON shapes and the empty case parse as described above.
- Command construction matches the oracle's `device_args` mapping and the flag list above.
- Tagged `Integration` test transcribes a short speech clip with the fixture model.
- An interrupted chunk leaves no partial segments in the database.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
