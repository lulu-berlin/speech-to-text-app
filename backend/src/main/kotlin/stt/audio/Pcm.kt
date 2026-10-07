package stt.audio

// Decoded PCM: little-endian float32, one channel, 16 kHz (ported from the oracle's `decode_pcm`).

internal const val SAMPLE_RATE = 16_000
internal const val BYTES_PER_SAMPLE = 4
internal const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
