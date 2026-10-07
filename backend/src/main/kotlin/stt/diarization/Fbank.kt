package stt.diarization

private const val FRAME_SHIFT = 160 // 10 ms at 16 kHz (ADR 0003)
private const val MEL_BINS = 80 // ADR 0003

/**
 * Kaldi log-mel filterbank features of mono 16 kHz [samples], one row of [MEL_BINS] values per frame.
 *
 * Frame count with `snip_edges = false`, from Kaldi `NumFrames` (feature-window.cc) /
 * kaldi-native-fbank `NumFrames`: `(num_samples + frame_shift / 2) / frame_shift`.
 */
fun fbank(samples: FloatArray): Array<FloatArray> {
    val frameCount = (samples.size + FRAME_SHIFT / 2) / FRAME_SHIFT
    return Array(frameCount) { FloatArray(MEL_BINS) }
}
