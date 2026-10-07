package stt.transcribe

import stt.db.Chunk
import stt.db.ChunkStatus

/**
 * Task progress (0.0–1.0) weighted by chunk duration: DONE chunks count in full, the running chunk
 * [runningIdx] by its [runningFraction], pending chunks not at all.
 */
fun taskProgress(
    chunks: List<Chunk>,
    runningIdx: Int,
    runningFraction: Double,
): Double {
    val totalMs = chunks.sumOf { it.durationMs() }
    val doneMs =
        chunks.sumOf { chunk ->
            when {
                chunk.status == ChunkStatus.DONE -> chunk.durationMs().toDouble()
                chunk.idx == runningIdx -> chunk.durationMs() * runningFraction
                else -> 0.0
            }
        }
    return doneMs / totalMs
}

private fun Chunk.durationMs(): Long = endMs - startMs
