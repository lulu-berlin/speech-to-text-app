package stt.audio

/**
 * The result of chunking a source (item 44): its decoded duration, its chunks in order as `(startMs, endMs)`
 * windows, and the canonical clip they are windows into. The engine stores the windows and the clip; no chunk
 * has audio of its own.
 */
data class ChunkedAudio(
    val durationMs: Long,
    val chunks: List<PlannedChunk>,
    val canonical: FlacRows,
)
