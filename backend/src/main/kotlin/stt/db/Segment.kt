package stt.db

/**
 * One transcript segment of a task; offsets in milliseconds. [speakerId] is its task's 0-based speaker index
 * ([Speaker]), null until diarized and with speakers off (ADR 0028).
 */
data class Segment(
    val chunkIdx: Int,
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: Int?,
)
