package stt.transcribe

/** One segment of a whisper-cli run; offsets in milliseconds relative to the transcribed file. */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
