package stt.diarization

/** One segment of a [SpeakerTurn]: its own offsets in milliseconds and its trimmed text (ADR 0030). */
internal data class TurnSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    // The stored row's id (item 52), set when the segment comes from the database.
    val id: Long? = null,
    // Whether the stored text differs from whisper's original (ADR 0061): the client offers Revert then.
    val edited: Boolean = false,
)
