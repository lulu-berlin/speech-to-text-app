package stt.diarization

/**
 * Consecutive segments of one speaker (item 09's turns): labelled with the speaker's stored name [speaker],
 * from the first segment's start to the last one's end, [text] the trimmed segment texts joined with single spaces;
 * [segments] keeps each segment's own start, end and trimmed text (ADR 0030).
 */
internal data class SpeakerTurn(
    val speakerId: Int,
    val speaker: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val segments: List<TurnSegment>,
)
