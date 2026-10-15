# 0052 — Audio storage: one canonical clip per task in the database; chunks are windows into it

Status: accepted · Date: 2026-10-14 · Supersedes: [0024](0024-audio-storage-original-until-chunking-flac-chunks.md), [0045](0045-joined-playback-flac-reencoded.md)

## Context

[ADR 0024](0024-audio-storage-original-until-chunking-flac-chunks.md) kept an upload's original in
`$STT_DATA_DIR/work/<id>/source` until chunking and stored one FLAC per chunk in the database (`chunk_audio`);
playback joined those FLACs into a cached file under `$STT_DATA_DIR/tmp`
([ADR 0045](0045-joined-playback-flac-reencoded.md) made the join re-encode so it seeks). The data directory
therefore held work directories and a cache besides the database, playback depended on a join that had stalled
and raced (items 37 and 38), and diarization rebuilt the whole recording from the chunk FLACs.

Roadmap item 44 ([docs/specs/audio-in-database.md](../specs/audio-in-database.md)) took the user's
request: "store the full audio clip in the right format and frequency and then the chunks are just pointers
(fromMs, toMs) and whenever we want to transcribe we can very cheaply create a temporary file that can be
immediately deleted after it's processed." The decisions below were made with the user during its consultation
and implementation (its "Open questions" and "Decisions during implementation" sections); this ADR records them.

## Decision

- **One canonical clip per task, in the database.** The upload is decoded once to a 16 kHz mono, 16-bit FLAC
  with 4000-sample (250 ms) frames (`-frame_size 4000`); ffmpeg does not pad the last frame, so it holds the
  remainder. The clip is stored deconstructed: its header (`fLaC` and the metadata) in `audio_header`, its frames
  in `audio_second` rows of up to four frames — one second each, the last row partial. Header plus rows in order
  is the encoded file byte for byte. The splitter accepts only that layout: consecutive frame numbers, header
  CRC-8 and frame CRC-16, only the last frame short, and frames that add up to STREAMINFO's sample count.
- **The encode writes to a file**, never a pipe: a piped FLAC leaves STREAMINFO's sample count at 0.
- **Chunks are `(start_ms, end_ms)` windows into the clip** (`chunks`), with no audio of their own. For each
  chunk, whisper-cli's input is cut from the clip: the header and the one-second rows the window touches go to a
  transient file, and ffmpeg trims the window's samples (`atrim` by sample count) into the input whisper reads; both
  files are removed after the chunk, whether it succeeds or fails.
- **The plan and the clip are stored together**, in one transaction; a resumed task reuses a stored plan only
  with its whole clip (header and rows) and otherwise chunks again from its original.
- **The upload streams into the database** window by window (`original_audio`, `original_audio_part`); no spool
  file exists. The original is decoded from a transient file (MP4-family uploads need a seekable input) and is
  deleted from the database once the plan and the clip are stored.
- **Playback serves the stored clip directly** (`/audio`), with Range support and `Content-Length`; there is no
  join, no re-encode and no cache.
- **Diarization decodes the stored clip.**
- **Every temporary file lives in the app's scratch directory** under the OS temp dir,
  `java.io.tmpdir/stt-<hash of STT_DATA_DIR>/`: per-task scratch, whisper inputs, the clip decoded for diarization,
  and the upgrade's backfill ([ADR 0051](0051-canonical-clip-backfill-migration.md)). Each is removed as it is used,
  and the whole directory is emptied at every start, so a crash leaves nothing behind.
- **Upgrades:** V9 adds the clip tables, V10 rebuilds every chunked task's clip from its chunk FLACs (ADR 0051),
  and V11 drops `chunk_audio`.

## Consequences

- The data directory is the database alone (with its write-ahead log); a backup after a clean stop is `stt.db`.
- The database holds each task's audio once: about 1.8 % over the raw FLAC for the one-second rows.
- Transcription runs one extra ffmpeg cut per chunk; playback and diarization run none for the join.
- ADR 0024's work directories, chunk FLACs and joined-playback cache, and ADR 0045's re-encoded join, are gone.
