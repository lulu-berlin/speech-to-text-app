package stt.db

/** Stored facts about a task's audio payload; [sha256] is lowercase hex. */
data class AudioMetadata(
    val sizeBytes: Long,
    val sha256: String,
)
