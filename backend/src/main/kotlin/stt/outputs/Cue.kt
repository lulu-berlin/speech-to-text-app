package stt.outputs

/** One srt/vtt cue from [startMs] to [endMs]; [text] already carries the speaker label when speakers are on. */
internal data class Cue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
