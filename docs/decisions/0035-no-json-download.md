# 0035 — No json transcript download

Status: accepted · Date: 2026-10-09

## Context
The oracle (`docs/oracle/transcribe.py`) writes txt, srt, vtt and json, and [ADR 0002](0002-no-python-oracle-only.md) listed all four as behavior to port. The oracle's json follows no published schema: it is whisper's segment list (`start`, `end`, `text`) plus a `speakers` count and a 0-based `speaker` number per segment. Nothing in the app consumes it. Since [ADR 0034](0034-speaker-labels-are-stored-names.md) every other download shows speaker names, while the json would still carry numbers. Structured transcript data is already served by item 12's `GET /api/tasks/{id}/turns` (speakers with their names, turns with their segments). The user decided to drop the json download (item 10 consultation, 2026-10-09: "YAGNI").

## Decision
- The transcript downloads are txt, srt and vtt. There is no json download and no json renderer.
- ADR 0002's list of oracle output formats to port is narrowed to txt, srt and vtt.
- ADR 0030's json point (`segments[].text` trimmed in the json download) no longer applies; its rule for every other output and the API stands.

## Consequences
- Item 10 removes its json renderer and tests; the oracle's json shape, Python float and string escaping, and its no-speech json literal are not ported.
- Item 12's download endpoint accepts `format=txt|srt|vtt`; item 15 offers three download buttons; item 22's note on the json download no longer applies.
- A json download can be added later from a real use case, with its own schema decision.
