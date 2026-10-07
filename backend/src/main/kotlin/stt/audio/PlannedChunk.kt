package stt.audio

/** One planned chunk of the source audio, in milliseconds from the start of the file; [endMs] is exclusive. */
data class PlannedChunk(
    val startMs: Long,
    val endMs: Long,
) {
    val lengthMs: Long get() = endMs - startMs
}
