package stt.transcribe

/** A chunk could not be transcribed (the oracle's `transcribe_one` failure cases). */
class TranscriptionException(
    message: String,
) : RuntimeException(message)
