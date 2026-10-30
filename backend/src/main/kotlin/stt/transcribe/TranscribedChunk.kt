package stt.transcribe

/**
 * One chunk's whisper-cli output: its [segments] and the language whisper detected in that chunk, or null when
 * the JSON carried none. Item 74: the detected language is what a task's transcript is marked with.
 */
data class TranscribedChunk(
    val segments: List<WhisperSegment>,
    val detectedLanguage: String?,
)
