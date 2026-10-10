package stt.db

/** One speaker of a task: [speakerId] is the 0-based index the diarization assigns, [speaker] its name (ADR 0028). */
data class Speaker(
    val speakerId: Int,
    val speaker: String,
)

/** The name the app gives speaker [speakerId] (ADR 0028): `"Speaker N"`, N = [speakerId] + 1; a name reset writes it again. */
fun defaultSpeakerName(speakerId: Int): String = "Speaker ${speakerId + 1}"
