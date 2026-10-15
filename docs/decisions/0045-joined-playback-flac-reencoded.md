# 0045 — The joined playback FLAC is re-encoded (losslessly)

Status: superseded by [0052](0052-canonical-clip-in-database-chunks-as-windows.md) (was accepted, amending the playback clause of [0024](0024-audio-storage-original-until-chunking-flac-chunks.md); playback now serves the stored clip, with no join) · Date: 2026-10-11

## Context

ADR 0024 joins a task's stored chunk FLACs for `GET /api/tasks/{id}/audio` with ffmpeg's concat demuxer and
`-c copy`, without re-encoding. The user found in the browser (item 29) that in a 30-minute task, seeking by clicking
the transcript stops working after about 5 minutes, the chunk length (ADR 0004). A copied concat has two defects,
measured in the workspace with the devenv's ffmpeg:

- The header (STREAMINFO) is the first chunk's: a join of six 5-minute chunks declares 300 s, not 1800 s, so the
  browser takes the first chunk's length as the file's.
- Every FLAC frame carries its own sample position, and the copied frames keep their chunk's numbering: positions
  restart at 0 at each chunk boundary (ffmpeg reports non-monotonic timestamps when decoding the join). A browser
  seeking in a FLAC searches those positions, so a target past the first boundary cannot be found.

Fixing only the header would leave the second defect.

## Decision

The join re-encodes to FLAC (`-c:a flac` instead of `-c copy`), still through the concat demuxer, still served as
`audio/flac`. FLAC is lossless, so the joined audio is sample-identical to the copy (checked: the decoded samples of
both joins are byte-identical). The re-encoded file declares its full length and numbers its frames continuously.

## Consequences

- Seeking works across the whole file, at the cost of an encode per join: about 2 s for 30 minutes (0.34 s for the
  copy) in the workspace measurement, once per task, since the result is cached in `tmp/<task-id>.flac` (item 12).
  The file size is unchanged (71 MB for both).
- A joined file cached before this change keeps its defects until the next startup, which empties `tmp/`.
- Storage (ADR 0024's chunks), the API contract and the frontend are unchanged.
