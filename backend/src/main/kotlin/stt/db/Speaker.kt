package stt.db

/** One speaker of a task: [speakerId] is the 0-based index the diarization assigns, [speaker] its name (ADR 0028). */
data class Speaker(
    val speakerId: Int,
    val speaker: String,
)
