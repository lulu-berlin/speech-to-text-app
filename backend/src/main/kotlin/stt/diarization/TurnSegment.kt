package stt.diarization

/** One segment of a [SpeakerTurn]: its own offsets in milliseconds and its trimmed text (ADR 0030). */
internal data class TurnSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
