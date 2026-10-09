# 10 — Output formats

Status: draft
Depends on: [09](09-diarization.md)

## Goal

Render a task's stored segments (and speaker labels) as txt, srt, vtt, and json, byte-for-byte identical to what the oracle's `write_diarized_outputs` writes for the same segments and speakers (`docs/oracle/transcribe.py`).

## Already decided

- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): output formats are part of the oracle behavior to port.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): speaker labels and turn merging come from item 09.
- [ADR 0030 — Segment text is trimmed in every output](../docs/decisions/0030-trimmed-segment-text-in-all-outputs.md) (2026-10-09): json `segments[].text` is trimmed too, a deliberate deviation from the oracle; everything else stays a byte-for-byte port.

## Proposal (pending approval)

Pure renderer functions `(segments, speakers?) -> String` (UTF-8), called on demand from the `segments` table (item 03 proposal: render on demand, nothing stored). Segment times are integer milliseconds in the DB; segment text is stored exactly as whisper-cli emitted it (leading space included), because item 07's prompt is built from it; every renderer trims it (ADR 0030).

Diarized formats (port of oracle `write_diarized_outputs`, using `speaker_turns` from item 09):

- **txt** — segment-level, not turn-level. Whenever the speaker differs from the previous segment's: a blank line (except before the first block), then `[speaker N]` (lower-case, `N` = index + 1) on its own line. Every segment's trimmed text on its own line. File ends with a single `\n` after the last text line.
  ```
  [speaker 1]
  Hello there.
  How are you?

  [speaker 2]
  Fine.
  ```
- **srt** — one cue per speaker turn, numbered from 1: `{i}\n{start} --> {end}\n{Speaker N}: {turn text}\n\n` (every cue, including the last, ends with a blank line).
- **vtt** — header `WEBVTT\n\n`, then per turn `{start} --> {end}\n{Speaker N}: {turn text}\n\n` (no cue numbers).
- **Timestamps** — oracle `fmt_srt` = `HH:MM:SS,mmm`, `fmt_vtt` = `HH:MM:SS.mmm`, computed as `ms = int(round(t * 1000))` then `divmod` by 3 600 000 / 60 000 / 1000; hours are zero-padded to two digits and never wrap (100 h → `100:00:00,000`). Because the app stores integer milliseconds, the renderer formats the stored value directly, which equals the oracle's result for any time that came from whisper-cli's millisecond offsets. (Python `round` is half-to-even; only relevant where seconds are converted to ms, i.e. item 07's legacy-JSON path.)
- **json** — Python `json.dump(..., ensure_ascii=False, indent=2)` output, no trailing newline:
  - top level keys in order `text`, `speakers`, `segments`;
  - `text` = every segment's text trimmed, joined with single spaces, trimmed;
  - `speakers` = number of distinct speaker labels;
  - `segments[]` = per segment, keys in order `start`, `end`, `text`, `speaker` (the oracle also copies `id`/`seek` before them when the legacy whisper JSON shape provides them; the app does not store them, so they are omitted); `start`/`end` in seconds (`ms / 1000.0`) printed like Python `repr(float)` (`0.0`, `1.5`, `12.34`); `text` trimmed (ADR 0030; the oracle keeps it untrimmed); `speaker` = 0-based index (not +1);
  - formatting: two-space indentation, `": "` between key and value, `,` at line ends, empty list printed as `[]`, non-ASCII characters written as-is, string escapes as Python's encoder emits them (`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, other characters below U+0020 as `\u00XX`).
- **No speech** (task with zero segments; oracle `transcribe_one`): json = `{"text": "", "segments": []}` followed by `\n` (compact, no `speakers` key); txt, srt, vtt = empty (0 bytes, vtt without header).
- **Speakers off** — variants without speaker labels (the oracle delegates these to whisper-cli's native `-otxt/-osrt/-ovtt/-oj` writers, so there is no oracle code to port). Draft: txt = one trimmed segment text per line; srt/vtt = one cue per segment with the trimmed text and no `Speaker N:` prefix; json = same shape as above without `speakers` and without per-segment `speaker`. Final shapes depend on the open question below.

## Open questions for the user

- Exact speakers-off formats: match whisper-cli 1.9.2's native writers byte-for-byte (captured as golden files from a real run), or use the draft above?
- No-speech output with speakers off: same as the diarized no-speech output, or what whisper-cli writes for an empty transcript?
- Should `id`/`seek` ever appear in json (they would require storing extra whisper fields)? Proposed: no.
- Download file names: `<original filename stem>.<format>` like the oracle's output prefix?
- Speaker names (ADR 0028, item 09): speakers are stored as `speakers(task_id, speaker_id, speaker)` with the name `"Speaker N"`, and a later feature will rename them. Should txt/srt/vtt print the stored name (identical output until a rename exists; txt's lower-case `[speaker N]` would then become `[<name>]`), or always the oracle's `Speaker N` / `[speaker N]` from the 0-based `speakerId`? json keeps the 0-based `speaker` number either way (oracle format).

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] txt starts a `[speaker N]` block at every speaker change, separates blocks with one blank line, and prints one trimmed segment per line
- [ ] srt merges consecutive same-speaker segments into one numbered cue `Speaker N: text` with first start and last end
- [ ] vtt starts with `WEBVTT` and a blank line and contains the same turns without cue numbers
- [ ] srt uses a comma and vtt a dot before milliseconds
- [ ] Timestamps roll over correctly at minutes and hours (3 600 000 ms → `01:00:00,000`) and hours above 99 are not wrapped
- [ ] Timestamps from milliseconds equal the oracle's `round(t * 1000)` result (e.g. 1 234 ms → `00:00:01,234`)
- [ ] json has `text`, `speakers`, `segments` in that order, 2-space indentation, no trailing newline, seconds as Python-style floats, trimmed segment text (ADR 0030), 0-based speaker
- [ ] json keeps non-ASCII text unescaped and escapes quotes, backslashes, and control characters like Python
- [ ] `speakers` counts distinct labels, not the highest label
- [ ] A task with no segments renders `{"text": "", "segments": []}\n` as json and empty txt/srt/vtt
- [ ] Speakers-off variants contain no speaker labels (shape per the answered open question)

## Acceptance criteria

- Golden-string tests per format and mode pass; the golden strings are derived by hand from the oracle's code (not copied from the Kotlin output).
- For a given segment list and speaker list, each diarized format is byte-identical to what `write_diarized_outputs` would write (verified by hand derivation, since the oracle is never executed).
- Renderers are pure functions with no DB or file I/O.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: decision from the item 12 contract consultation ([ADR 0030](../docs/decisions/0030-trimmed-segment-text-in-all-outputs.md)): json `segments[].text` is trimmed, like every other output; storage stays untrimmed for item 07's prompt. The rest of the item is still draft.
