package stt.transcribe

/** A chunk could not be transcribed (the transcriber's failure cases). */
class TranscriptionException(
    message: String,
) : RuntimeException(message)
