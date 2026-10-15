-- Roadmap item 44: chunks are (start_ms, end_ms) windows into the task's canonical clip (audio_header +
-- audio_second, V9), and whisper's input for a chunk is cut from that clip, so no chunk keeps audio of its own.
-- V10 has just rebuilt the clip of every task that had chunk_audio rows (a failed rebuild aborts the upgrade
-- before this runs), so the per-chunk FLACs hold nothing the clip does not: the table goes.
DROP TABLE chunk_audio;
