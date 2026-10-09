# 0034 — Speaker labels in downloads are the stored speaker names

Status: accepted · Date: 2026-10-09

## Context
The oracle (`docs/oracle/transcribe.py`, ADR 0002) labels speakers from their 0-based index: `Speaker N` in srt/vtt cues and `[speaker N]` (lower-case) as txt block headers, N = index + 1. Item 09 stores every speaker of a task with a name, initially `"Speaker N"` ([ADR 0028](0028-speakers-table-and-segment-speaker-id.md)), and item 22 will let the user rename speakers. Item 10's approved spec printed the oracle's labels from the speakerId and left renamed names to item 22. The user decided that the name is the label: `"Speaker N"` is a placeholder, and a speaker's real name, once given, must appear everywhere (item 10 consultation, 2026-10-09).

## Decision
- txt, srt and vtt label a speaker with its stored name (`Speaker.speaker`):
  - txt block header `[{name}]`, e.g. `[Speaker 1]` or `[Alice]`;
  - srt and vtt cue text `{name}: {turn text}`, e.g. `Speaker 1: Hello.` or `Alice: Hello.`.
- No special case for the oracle's lower-case `[speaker N]`: txt always prints the name as stored, so the default header is `[Speaker 1]`.
- Turns still merge by `speakerId` (item 09), so two speakers renamed alike stay separate turns.

## Consequences
- While no speaker is renamed, srt and vtt stay byte-identical to the oracle; txt differs in one character per block header (`S` instead of `s`).
- A rename (item 22) changes the downloads without touching any segment.
- Item 22's "Downloads" bullet is settled by this ADR. There is no json download ([ADR 0035](0035-no-json-download.md)), so no download carries the 0-based number.
