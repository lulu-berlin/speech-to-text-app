package stt.db

/** One planned slice of a task's audio (ADR 0004); offsets in milliseconds. */
data class Chunk(
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val status: ChunkStatus,
)
