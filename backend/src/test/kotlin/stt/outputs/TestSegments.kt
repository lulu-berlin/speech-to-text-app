package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/** The stored speakers for [speakerIds], named "Speaker N" as item 09 stores them. */
internal fun speakers(vararg speakerIds: Int) = speakerIds.map { Speaker(speakerId = it, speaker = "Speaker ${it + 1}") }

internal fun segment(
    speakerId: Int?,
    text: String,
    startMs: Long = 0,
    endMs: Long = 0,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = text, speakerId = speakerId)
