package stt.diarization

import stt.db.Segment

/** A not-yet-diarized segment from [startMs] to [endMs] with no text; chunk and seq do not matter to diarization. */
internal fun segment(
    startMs: Long,
    endMs: Long,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speakerId = null)
