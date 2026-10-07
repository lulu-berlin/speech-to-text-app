package stt.audio

/** A silence detected by ffmpeg `silencedetect`, in milliseconds from the start of the file. */
data class Silence(
    val startMs: Long,
    val endMs: Long,
) {
    val midpointMs: Long get() = (startMs + endMs) / 2
}
