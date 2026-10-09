# 10 — Output formats

Status: in-progress
Depends on: [09](../docs/specs/diarization.md) (done)

## Goal

Render a task's stored segments (and speaker labels) as txt, srt, vtt, and json, byte-for-byte identical to what the oracle's `write_diarized_outputs` writes for the same segments and speakers (`docs/oracle/transcribe.py`).

## Already decided

- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): output formats are part of the oracle behavior to port.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): speaker labels and turn merging come from item 09.
- [ADR 0030 — Segment text is trimmed in every output](../docs/decisions/0030-trimmed-segment-text-in-all-outputs.md) (2026-10-09): json `segments[].text` is trimmed too, a deliberate deviation from the oracle; everything else stays a byte-for-byte port.

## Proposal (approved 2026-10-09)

Pure renderer functions `(segments) -> String` (UTF-8), called on demand from the `segments` table (item 03 proposal: render on demand, nothing stored). Each stored segment carries its `speakerId` (item 09, ADR 0028): null for every segment of a speakers-off task. Segment times are integer milliseconds in the DB. Segment text is stored exactly as whisper-cli emitted it (leading space included), because item 07's prompt is built from it; every renderer trims it (ADR 0030).

Diarized formats (port of oracle `write_diarized_outputs`). Labels always come from the 0-based `speakerId` as the oracle prints them, `Speaker N` / `[speaker N]` with N = speakerId + 1, never from the stored speaker name (decided 2026-10-09; item 22 revisits this when names become renamable). Turns are item 09's `speakerTurns`, whose consecutive segments share a `speakerId`; the renderer labels each turn from its `speakerId`, not from `SpeakerTurn.speaker`.

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
  - `segments[]` = per segment, keys in order `start`, `end`, `text`, `speaker`. The oracle copies `id`/`seek` only from whisper's legacy `segments` JSON; whisper-cli 1.9.2 writes the `transcription` shape, from which the oracle builds no `id`/`seek`, so they are omitted (decided 2026-10-09). `start`/`end` are in seconds (`ms / 1000.0`), printed like Python `repr(float)` (`0.0`, `1.5`, `12.34`); `text` is trimmed (ADR 0030; the oracle keeps it untrimmed); `speaker` is the 0-based index (not +1);
  - formatting: two-space indentation, `": "` between key and value, `,` at line ends, empty list printed as `[]`, non-ASCII characters written as-is, string escapes as Python's encoder emits them (`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, other characters below U+0020 as `\u00XX`).
- **No speech** (task with zero segments; oracle `transcribe_one`): json = `{"text": "", "segments": []}` followed by `\n` (compact, no `speakers` key); txt, srt, vtt = empty (0 bytes, vtt without header). The same with speakers on or off (decided 2026-10-09).
- **Speakers off** (decided 2026-10-09). The oracle delegates these to whisper-cli's native writers, so there is no oracle code to port. The app uses the diarized shapes without labels, with trimmed text (ADR 0030), not whisper-cli's own output, which keeps the leading spaces and writes whisper's raw JSON:
  - txt: one trimmed segment text per line, no `[speaker N]` lines, ending with a single `\n`;
  - srt: one numbered cue per segment, `{i}\n{start} --> {end}\n{text}\n\n`;
  - vtt: `WEBVTT\n\n`, then one cue per segment, `{start} --> {end}\n{text}\n\n`;
  - json: top-level keys `text`, `segments`; per segment `start`, `end`, `text`; no `speakers`, no `speaker`; the same formatting rules as the diarized json.
- **Download file names** (decided 2026-10-09): `<original filename stem>.<format>`, like the oracle's output prefix (`interview.mp3` → `interview.srt`). This item provides the name; item 12 sends it in `Content-Disposition`.

## Open questions (answered 2026-10-09)

- Speakers-off formats? → **The draft**: the diarized shapes without labels, trimmed; not whisper-cli's native output.
- No-speech output with speakers off? → **Same as the diarized no-speech output.**
- `id`/`seek` in the downloaded json? → **Omitted.** The oracle itself prints none with whisper-cli 1.9.2's JSON.
- Download file names? → **`<original stem>.<format>`.**
- Speaker labels? → **The oracle's `Speaker N` / `[speaker N]` from the speakerId**; item 22 decides once names are renamable.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] txt starts a `[speaker N]` block at every speaker change, separates blocks with one blank line, and prints one trimmed segment per line
- [ ] srt merges consecutive same-speaker segments into one numbered cue `Speaker N: text` with first start and last end
- [ ] vtt starts with `WEBVTT` and a blank line and contains the same turns without cue numbers
- [ ] srt uses a comma and vtt a dot before milliseconds
- [ ] Timestamps roll over correctly at minutes and hours (3 600 000 ms → `01:00:00,000`) and hours above 99 are not wrapped
- [ ] Timestamps from milliseconds equal the oracle's `round(t * 1000)` result (e.g. 1 234 ms → `00:00:01,234`)
- [ ] json has `text`, `speakers`, `segments` in that order, 2-space indentation, no trailing newline, seconds as Python-style floats, trimmed segment text (ADR 0030), 0-based speaker
- [ ] json keeps non-ASCII text unescaped and escapes quotes, backslashes, and control characters like Python
- [ ] `speakers` counts distinct labels, not the highest label
- [ ] A task with no segments renders `{"text": "", "segments": []}\n` as json and empty txt/srt/vtt
- [ ] Speakers off: txt is one trimmed segment per line, srt and vtt have one cue per segment without a speaker prefix, and json has `text` and `segments` (start, end, text) without `speakers`/`speaker`
- [ ] Speakers off with no segments renders the same no-speech output as speakers on
- [ ] Labels come from the speakerId (`Speaker N`, `[speaker N]`), not from the stored speaker name
- [ ] The download file name is the original file's stem plus the format's extension

## Acceptance criteria

- Golden-string tests per format and mode pass; the golden strings are derived by hand from the oracle's code (not copied from the Kotlin output).
- For a given segment list and speaker list, each diarized format is byte-identical to what `write_diarized_outputs` would write (verified by hand derivation, since the oracle is never executed).
- Renderers are pure functions with no DB or file I/O.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: decision from the item 12 contract consultation ([ADR 0030](../docs/decisions/0030-trimmed-segment-text-in-all-outputs.md)): json `segments[].text` is trimmed, like every other output; storage stays untrimmed for item 07's prompt. The rest of the item is still draft.
- 2026-10-09: consultation answers approved: speakers-off formats = the draft (diarized shapes without labels, trimmed); speakers-off no-speech = the diarized no-speech output; `id`/`seek` omitted; download names `<original stem>.<format>`; labels from the speakerId as the oracle prints them, with item 22 revisiting them for renamed speakers. Test list not yet approved.
- 2026-10-09: test list (14 items) approved by the user; status `spec-approved`.
- 2026-10-09 (cycle 1, test-list item 1, txt speaker blocks): red — `gradle test --tests 'stt.outputs.TxtSpec'`: compilation failed, "Unresolved reference 'renderTxt'". Green: `renderTxt(segments)` (new `stt.outputs`, `Txt.kt`), a pure port of the oracle's txt branch: on a speakerId change a `\n` (except before the first block) and `[speaker {speakerId + 1}]\n`, then every segment's trimmed text and `\n`; a segment without a speakerId is rejected (speakers off is a later item). Fixture: speakerIds 0, 0, 1, 0 with texts `" Hello there."`, `"How are you? "`, `" Fine."`, `" Bye."`; golden string derived by hand from oracle lines 415–422: `"[speaker 1]\nHello there.\nHow are you?\n\n[speaker 2]\nFine.\n\n[speaker 1]\nBye.\n"` (the returning speaker 1 opens a new block). `gradle check` green (132 unit tests); no Integration-tagged code touched.
