package stt.db

/**
 * One stored row of a task's canonical audio (V9): the frames of one second, [frames] of them, each a 250 ms
 * frame of the task's 16 kHz mono FLAC — except the clip's very last frame, which holds the remaining samples. Row
 * [second] covers `[second·1000, (second+1)·1000)` ms; the last row may cover less.
 */
data class CanonicalAudio(
    val second: Int,
    val frames: Int,
    /** The row's frame bytes, in order. */
    val bytes: ByteArray,
)
