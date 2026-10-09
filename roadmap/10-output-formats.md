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
- [x] srt merges consecutive same-speaker segments into one numbered cue `Speaker N: text` with first start and last end
- [x] vtt starts with `WEBVTT` and a blank line and contains the same turns without cue numbers
- [x] srt uses a comma and vtt a dot before milliseconds
- [x] Timestamps roll over correctly at minutes and hours (3 600 000 ms → `01:00:00,000`) and hours above 99 are not wrapped
- [x] Timestamps from milliseconds equal the oracle's `round(t * 1000)` result (e.g. 1 234 ms → `00:00:01,234`)
- [x] json has `text`, `speakers`, `segments` in that order, 2-space indentation, no trailing newline, seconds as Python-style floats, trimmed segment text (ADR 0030), 0-based speaker
- [x] (added 2026-10-09) json `text` is the plain join of every trimmed segment text, so a blank segment leaves two adjacent spaces
- [x] json keeps non-ASCII text unescaped and escapes quotes, backslashes, and control characters like Python
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
- 2026-10-09: refactor approved and done after cycle 1: `Txt.kt` → `RenderTxt.kt`, named after its function like the other single-function files (`SpeakerTurns.kt`, `ParseSegments.kt`). File name only. `gradle check` green (132 unit tests).
- 2026-10-09 (cycle 2, test-list item 2, srt turns): red — `gradle test --tests 'stt.outputs.RenderSrtSpec'`: compilation failed, "Unresolved reference 'renderSrt'". Green: `renderSrt(segments, speakers)` (`RenderSrt.kt`) labels each `speakerTurns` turn `Speaker {speakerId + 1}: {text}` with a 1-based number and `fmtSrt` bounds, every cue ending in a blank line; `fmtSrt(ms)` (`FmtSrt.kt`) is the oracle's `divmod` on the stored milliseconds, `%02d:%02d:%02d,%03d` in `Locale.ROOT`. The renderer takes the stored `speakers` (design (a), user 2026-10-09) so the label source is testable in item 13. Fixture: speakerIds 0, 0, 1; 0–1 500, 1 500–3 250, 3 250–4 000 ms; texts `" Hello there."`, `" again."`, `" Fine."`; golden string derived by hand from oracle lines 425–427: `"1\n00:00:00,000 --> 00:00:03,250\nSpeaker 1: Hello there. again.\n\n2\n00:00:03,250 --> 00:00:04,000\nSpeaker 2: Fine.\n\n"`. `gradle check` green (133 unit tests); no Integration-tagged code touched.
- 2026-10-09: refactor approved and done after cycle 2: `TxtSpec.kt` → `RenderTxtSpec.kt` (file and class), named after its `RenderTxt.kt` subject like the other specs. Name only.
- 2026-10-09: refactor approved and done after cycle 2: the identical private `segment(...)` helpers of `RenderTxtSpec` and `RenderSrtSpec` become one internal helper in the new test file `stt/outputs/TestSegments.kt` (`startMs`/`endMs` default 0), as item 09 did; the imports left unused by the move are removed. `gradle check` green (133 unit tests).
- 2026-10-09 (cycle 3, test-list item 3, vtt turns): red — `gradle test --tests 'stt.outputs.RenderVttSpec'`: compilation failed, "Unresolved reference 'renderVtt'". Green: `renderVtt(segments, speakers)` (`RenderVtt.kt`) writes the `WEBVTT\n\n` header then one cue per `speakerTurns` turn, `Speaker {speakerId + 1}: {text}` with `fmtVtt` bounds and no cue number, every cue ending in a blank line; `fmtVtt(ms)` (`FmtVtt.kt`) is the oracle's `fmt_vtt`, `%02d:%02d:%02d.%03d` in `Locale.ROOT` (kept as its own function like the oracle's). Same fixture as cycle 2; golden string derived by hand from oracle lines 429–433: `"WEBVTT\n\n00:00:00.000 --> 00:00:03.250\nSpeaker 1: Hello there. again.\n\n00:00:03.250 --> 00:00:04.000\nSpeaker 2: Fine.\n\n"`. `gradle check` green (134 unit tests); no Integration-tagged code touched.
- 2026-10-09 (test-list item 4, srt comma vs vtt dot): already covered — cycle 2's golden string asserts `00:00:03,250` in srt and cycle 3's asserts `00:00:03.250` in vtt, so no new test was written (user decision, option (a)). Red recorded against a temporary, uncommitted mutation of `fmtVtt` to the comma separator: `gradle test --tests 'stt.outputs.RenderVttSpec'`, "expected:<WEBVTT\n\n00:00:00.000 --> 00:00:03.250 …> but was:<WEBVTT\n\n00:00:00,000 --> 00:00:03,250 …>". No production change.
- 2026-10-09 (cycle 5, test-list item 5, timestamp rollover): green on arrival (`fmtSrt`/`fmtVtt` already do the oracle's `divmod`); red recorded against a temporary, uncommitted mutation `ms / 3_600_000` → `ms / 3_600_000 % 100` in `formatTimestamp`: `gradle test --tests 'stt.outputs.TimestampsSpec'`, "expected:<100:00:00,000> but was:<00:00:00,000>" (both the srt and the vtt assertion). New `TimestampsSpec` asserts the hand-derived oracle values 0, 59 999, 60 000, 3 599 999, 3 600 000, 3 600 001 ms and 360 000 000 ms (= 100 h, not wrapped) for srt, and 3 600 000 / 360 000 000 ms for vtt. No production change. `gradle check` green (135 unit tests); no Integration-tagged code touched.
- 2026-10-09: refactor approved and done after cycle 5: `RenderSrtSpec` and `RenderVttSpec` share the `speakers1And2` fixture in `stt/outputs/TestSegments.kt` (the duplicated speaker list), as item 09 did in `SegmentRepositorySpec`; the unused `Speaker` imports are removed. `gradle check` green (135 unit tests).
- 2026-10-09 (cycle 6, test-list item 6, milliseconds equal the oracle's rounding): green on arrival — the renderer formats the stored integer milliseconds directly, which equals the oracle's `int(round(t * 1000))` for whisper-cli's offsets; red recorded against a temporary, uncommitted mutation `ms % 1000` → `ms % 100` in `formatTimestamp`: `gradle test --tests 'stt.outputs.TimestampsSpec'`, "expected:<00:00:01,234> but was:<00:00:01,034>" (the rollover scenario also fails, "expected:<00:00:59,999> but was:<00:00:59,099>"). New scenario in `TimestampsSpec`: `fmtSrt(1_234)` → `"00:00:01,234"` and `fmtVtt(1_234)` → `"00:00:01.234"` (t = 1.234 s → `1234` ms by hand). No production change. `gradle check` green (136 unit tests); no Integration-tagged code touched.
- 2026-10-09: test-list item added (approved by the user at the cycle 6 checkpoint, after the agent read the oracle's json branch while preparing item 7): "json `text` is the plain join of every trimmed segment text, so a blank segment leaves two adjacent spaces". Not a `(discovered)` item — the agent found it while reading oracle line 411, not while running a cycle; `text` joins all trimmed texts whereas `speakerTurns` drops blanks. It is inserted after item 7 and marked `(added 2026-10-09)`.
- 2026-10-09 (cycle 7, test-list item 7, json structure): red — `gradle test --tests 'stt.outputs.RenderJsonSpec'`: compilation failed, "Unresolved reference 'renderJson'". Green: `renderJson(segments)` (`RenderJson.kt`) builds the oracle's document (lines 408–411 and 435–446): `text` = the trimmed texts joined with single spaces then trimmed, `speakers` = distinct speakerIds, `segments` with `start`/`end` in seconds (`ms / 1000.0`), the trimmed `text` (ADR 0030) and the 0-based `speaker`; Python's `indent=2` layout with `": "`, two-space indentation, `[]` for an empty list and no trailing newline. Fixture: speakerIds 0, 0, 1; 0–1 500, 1 500–3 250, 3 250–4 000 ms; texts `" Hello there."`, `" again."`, `" Fine."`; golden string derived by hand (JDK 21 shortest-round-trip doubles `0.0`, `1.5`, `3.25`, `4.0` equal Python's `repr`). `jsonString` only wraps in quotes for now; escaping is item 8's cycle. `gradle check` green (137 unit tests); no Integration-tagged code touched.
- 2026-10-09 (cycle 8, added test-list item, json text join): green on arrival (the renderer already joins every trimmed text with single spaces); red recorded against a temporary, uncommitted mutation of the join to drop blanks (`.filter { it.isNotEmpty() }.joinToString(" ")`, the `speakerTurns` behaviour): `gradle test --tests 'stt.outputs.RenderJsonSpec'`, the document had `"text": "A B"` instead of `"A  B"`. New scenario in `RenderJsonSpec`: one speaker, texts `" A "`, `"  "` (blank) and `" B "`; golden string hand-derived from oracle line 411 → `"A  B"`. No production change. `gradle check` green (138 unit tests); no Integration-tagged code touched.
- 2026-10-09 (cycle 9, test-list item 8, json escaping): red — `gradle test --tests 'stt.outputs.RenderJsonSpec'`: the new scenario failed ("Contents did not match exactly") because `jsonString` wrote the text verbatim, a raw newline inside the string. Green: `jsonString` escapes `"` → `\"` and `\` → `\\`, uses the short escapes `\b \f \n \r \t`, writes every other character below U+0020 as `\u00xx` (lower-case hex, `char.code.toString(16).padStart(4, '0')`) and everything else, non-ASCII included, as-is — Python's `encode_basestring` under `ensure_ascii=False` (its `ESCAPE_DCT` has exactly these entries, read from `json/encoder.py`; no U+2028 escaping). Fixture: one segment, text `" A \"quoted\" back\\slash\nnewline\ttab\u0001ctrl ü 日 "` (trimmed → `A "quoted" back\slash<LF>newline<TAB>tab<U+0001>ctrl ü 日`); golden string derived by hand from the escape table. `gradle check` green (139 unit tests); no Integration-tagged code touched.
