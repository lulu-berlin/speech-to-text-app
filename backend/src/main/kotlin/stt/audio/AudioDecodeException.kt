package stt.audio

/** The source audio could not be decoded to PCM (the decoder's failure cases). */
class AudioDecodeException(
    message: String,
) : RuntimeException(message)
