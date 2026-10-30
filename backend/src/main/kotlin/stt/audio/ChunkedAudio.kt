package stt.audio

import stt.db.CanonicalAudio

/**
 * The result of chunking a source (item 44): its decoded duration, its chunks in order as `(startMs, endMs)`
 * windows, and the canonical clip they are windows into. The engine stores the windows and the clip; no chunk
 * has audio of its own.
 */
data class ChunkedAudio(
    val durationMs: Long,
    val chunks: List<PlannedChunk>,
    val canonical: CanonicalRows,
)

/**
 * A canonical clip as it is stored (item 44): its header and its one-second rows. The rows are read on demand, one
 * at a time (item 71), so storing a long clip never holds it in the heap whole.
 */
interface CanonicalRows {
    val header: ByteArray

    /** Runs [block] over the clip's rows in `second` order; the rows can be read only while [block] runs. */
    suspend fun <T> useRows(block: suspend (Sequence<CanonicalAudio>) -> T): T
}
