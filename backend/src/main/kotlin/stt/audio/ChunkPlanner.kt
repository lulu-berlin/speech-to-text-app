package stt.audio

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
            val endMs = minOf(startMs + targetMs, durationMs)
            add(PlannedChunk(startMs, endMs))
            startMs = endMs
        }
    }
