package stt.audio

import kotlin.math.abs

/** A trailing chunk shorter than this is merged into the previous chunk. */
private const val SHORT_TAIL_MS = 30_000L

/** Plans contiguous chunks covering a file of [durationMs], cut at [silences] near [targetMs] and never longer than [capMs]. */
internal fun planChunks(
    durationMs: Long,
    silences: List<Silence>,
    targetMs: Long,
    capMs: Long,
): List<PlannedChunk> {
    val chunks =
        buildList {
            var startMs = 0L
            while (startMs < durationMs) {
                val endMs = if (durationMs - startMs <= targetMs) durationMs else nextCutMs(startMs, silences, targetMs, capMs)
                add(PlannedChunk(startMs, endMs))
                startMs = endMs
            }
        }
    return mergeShortTail(chunks, capMs)
}

/** The midpoint of the silence after [startMs] and within [capMs] nearest to `startMs + targetMs`, or that target point when none qualifies. */
private fun nextCutMs(
    startMs: Long,
    silences: List<Silence>,
    targetMs: Long,
    capMs: Long,
): Long {
    val targetEndMs = startMs + targetMs
    return silences
        .map { it.midpointMs }
        .filter { it > startMs && it <= startMs + capMs }
        .minByOrNull { abs(it - targetEndMs) } ?: targetEndMs
}

/** Merges a trailing chunk shorter than [SHORT_TAIL_MS] into the previous chunk when the merged chunk stays within [capMs]. */
private fun mergeShortTail(
    chunks: List<PlannedChunk>,
    capMs: Long,
): List<PlannedChunk> {
    if (chunks.size < 2) return chunks
    val tail = chunks.last()
    val merged = PlannedChunk(chunks[chunks.lastIndex - 1].startMs, tail.endMs)
    return if (tail.lengthMs < SHORT_TAIL_MS && merged.lengthMs <= capMs) chunks.dropLast(2) + merged else chunks
}
