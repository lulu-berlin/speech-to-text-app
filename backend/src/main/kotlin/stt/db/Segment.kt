package stt.db

/**
 * One transcript segment of a task; offsets in milliseconds. [speakerId] is its task's 0-based speaker index
 * ([Speaker]), null until diarized and with speakers off (ADR 0028). [id] is the row's id once it is stored
 * (item 52, which addresses an edit by it); null for a segment parsed from whisper-cli and not yet inserted.
 */
data class Segment(
    val chunkIdx: Int,
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: Int?,
    val id: Long? = null,
    /** Whisper's text as first stored (ADR 0061); [text] may have been edited away from it. */
    val originalText: String? = null,
)
