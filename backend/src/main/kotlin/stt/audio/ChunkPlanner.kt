package stt.audio

import kotlin.math.abs

/** A trailing chunk shorter than this is merged into the previous chunk. */
private const val SHORT_TAIL_MS = 30_000L

/** Plans contiguous chunks covering a file of [durationMs], cut at [silences] near [targetMs] and never longer than [capMs]. */
fun planChunks(
    durationMs: Long,
    silences: List<Silence>,
    targetMs: Long,
    capMs: Long,
): List<PlannedChunk> =
    buildList {
        var startMs = 0L
        while (startMs < durationMs) {
            val endMs = if (durationMs - startMs <= targetMs) durationMs else nextCutMs(startMs, silences, targetMs, capMs)
            add(PlannedChunk(startMs, endMs))
            startMs = endMs
        }
        if (size > 1 && last().let { it.endMs - it.startMs } < SHORT_TAIL_MS && durationMs - this[lastIndex - 1].startMs <= capMs) {
            val tail = removeAt(lastIndex)
            set(lastIndex, PlannedChunk(last().startMs, tail.endMs))
        }
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
