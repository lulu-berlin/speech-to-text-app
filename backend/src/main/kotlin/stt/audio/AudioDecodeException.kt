package stt.audio

/** The source audio could not be decoded to PCM (the oracle's `decode_pcm` failure cases). */
class AudioDecodeException(
    message: String,
) : RuntimeException(message)
