package stt.diarization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

private const val SAMPLE_RATE = 16_000f // ADR 0003
private const val FRAME_SHIFT = 160 // 10 ms at 16 kHz (ADR 0003)
private const val FFT_SIZE = 512 // 400-sample frame rounded up to a power of two (ADR 0003)
private const val MEL_BINS = 80 // ADR 0003
private const val LOW_FREQ = 20f // ADR 0003
private const val HIGH_FREQ = 8_000f - 400f // ADR 0003: Kaldi high_freq = -400, i.e. Nyquist (8 kHz) - 400 Hz

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

/**
 * Kaldi mel filterbank: [MEL_BINS] triangles, each a row of weights over the `FFT_SIZE / 2 + 1` power-spectrum bins.
 *
 * Ported from Kaldi `MelBanks::MelBanks` (mel-computations.cc) / kaldi-native-fbank `MelBanks` (mel-computations.cc),
 * single-precision like the originals: triangles are equally spaced on the mel scale between [LOW_FREQ] and
 * [HIGH_FREQ], triangle `b` spans the open interval `(mel_low + b * delta, mel_low + (b + 2) * delta)` with its
 * peak at `mel_low + (b + 1) * delta`, and only the first `FFT_SIZE / 2` bins are considered (never Nyquist).
 */
internal fun melFilterbank(): Array<FloatArray> {
    val fftBinWidth = SAMPLE_RATE / FFT_SIZE
    val melLow = melScale(LOW_FREQ)
    val melDelta = (melScale(HIGH_FREQ) - melLow) / (MEL_BINS + 1)
    return Array(MEL_BINS) { bin ->
        val leftMel = melLow + bin * melDelta
        val centerMel = melLow + (bin + 1) * melDelta
        val rightMel = melLow + (bin + 2) * melDelta
        val weights = FloatArray(FFT_SIZE / 2 + 1)
        for (i in 0 until FFT_SIZE / 2) {
            val mel = melScale(fftBinWidth * i)
            if (mel > leftMel && mel < rightMel) {
                weights[i] = if (mel <= centerMel) (mel - leftMel) / (centerMel - leftMel) else (rightMel - mel) / (rightMel - centerMel)
            }
        }
        weights
    }
}

/** Kaldi `MelScale` (mel-computations.h): `1127 * ln(1 + f / 700)`. */
private fun melScale(freq: Float): Float = 1127f * ln(1f + freq / 700f)
