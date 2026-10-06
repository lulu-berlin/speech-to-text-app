package stt.audio

import kotlin.math.abs

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
            val endMs =
                if (durationMs - startMs <= targetMs) {
                    durationMs
                } else {
                    val targetEndMs = startMs + targetMs
                    silences.map { (it.startMs + it.endMs) / 2 }.minByOrNull { abs(it - targetEndMs) } ?: targetEndMs
                }
            add(PlannedChunk(startMs, endMs))
            startMs = endMs
        }
    }
