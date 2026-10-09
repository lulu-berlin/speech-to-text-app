# 0030 — Segment text is trimmed in every output

Status: accepted (json download point dropped by [0035](0035-no-json-download.md)) · Date: 2026-10-09

## Context
whisper-cli writes each segment's text with a leading space. Item 07 stores it unchanged ([whisper-transcription spec](../specs/whisper-transcription.md): trimming happens when outputs are rendered). The oracle (`docs/oracle/transcribe.py`, ADR 0002) trims the text for txt, srt, vtt and the json `text` field, but copies each segment's untrimmed text into the json `segments[].text`. Item 10 planned to port that byte for byte.

Since then, the HTTP API serves segments to the frontend (item 12, `/turns`), and the transcript view joins segments and turns into continuous text. The user wants every text that reaches the frontend or the user, as data or as a downloaded file, to be trimmed, so that segments and turns join the same way everywhere (item 12/13 contract consultation, 2026-10-09).

## Decision
- Every segment text that leaves the backend is trimmed:
  - the API (`/turns` turn and segment texts);
  - every download format, including json `segments[].text`.
- Storage is unchanged. `segments.text` keeps whisper-cli's text as written, because item 07's `--prompt` is built from the previous chunk's raw segment texts, joined and then trimmed.
- Joining stays as before: trimmed texts joined with single spaces, then trimmed. This is how turns (item 09), the json `text` field and the txt/srt/vtt lines are built.

## Consequences
- The json download deviates from the oracle in one point: `segments[].text` is trimmed. Everything else in item 10's oracle port stays byte-for-byte, and the golden strings are derived with this one change.
- txt, srt, vtt and the json `text` field were already trimmed in the oracle and do not change.
- `docs/api/` examples show trimmed segment texts.
