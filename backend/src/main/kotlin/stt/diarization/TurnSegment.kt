package stt.diarization

/** One segment of a [SpeakerTurn]: its own offsets in milliseconds and its text as stored (untrimmed). */
internal data class TurnSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
