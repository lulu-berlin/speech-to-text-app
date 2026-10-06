package stt.db

/** One transcript segment of a task; offsets in milliseconds, [speaker] null until diarized. */
data class Segment(
    val chunkIdx: Int,
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String?,
)
