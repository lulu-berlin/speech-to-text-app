package stt.diarization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

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

/**
 * Kaldi "povey" window of [length] samples: `(0.5 - 0.5 * cos(2 * pi * n / (length - 1)))^0.85`,
 * from Kaldi `FeatureWindowFunction` (feature-window.cc) / kaldi-native-fbank `FeatureWindowFunction`
 * (feature-window.cc), computed in double and stored as float.
 */
internal fun poveyWindow(length: Int): FloatArray {
    val a = 2 * PI / (length - 1)
    return FloatArray(length) { n -> (0.5 - 0.5 * cos(a * n)).pow(0.85).toFloat() }
}
