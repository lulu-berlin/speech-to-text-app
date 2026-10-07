package stt.audio

/** The result of chunking a source: its decoded duration and its exported chunks in order. */
data class ChunkedAudio(
    val durationMs: Long,
    val chunks: List<ExportedChunk>,
)
