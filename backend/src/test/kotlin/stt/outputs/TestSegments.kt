package stt.outputs

import stt.db.Segment

internal fun segment(
    speakerId: Int,
    text: String,
    startMs: Long = 0,
    endMs: Long = 0,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = text, speakerId = speakerId)
