package stt.diarization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

private const val SAMPLE_RATE = 16_000f // ADR 0003
private const val FRAME_LENGTH = 400 // 25 ms at 16 kHz (ADR 0003)
private const val FRAME_SHIFT = 160 // 10 ms at 16 kHz (ADR 0003)
private const val FFT_SIZE = 512 // 400-sample frame rounded up to a power of two (ADR 0003)
private const val MEL_BINS = 80 // ADR 0003
private const val LOW_FREQ = 20f // ADR 0003
private const val HIGH_FREQ = 8_000f - 400f // ADR 0003: Kaldi high_freq = -400, i.e. Nyquist (8 kHz) - 400 Hz
private const val PREEMPHASIS = 0.97f // ADR 0003

/** Kaldi's floor before the log: `std::numeric_limits<float>::epsilon()` = 2^-23. */
private val LOG_FLOOR = Math.ulp(1f)

private val WINDOW = poveyWindow(FRAME_LENGTH)
private val MEL_BANK = melFilterbank()
private val FFT = Fft(FFT_SIZE)

/**
 * Kaldi log-mel filterbank features of mono 16 kHz [samples], one row of [MEL_BINS] values per frame.
 *
 * Ported from Kaldi `ExtractWindow`, `ProcessWindow` (feature-window.cc) and `FbankComputer::Compute` (feature-fbank.cc),
 * equally kaldi-native-fbank, with the ADR 0003 options (dither 0, remove_dc_offset, use_power, use_log, no energy):
 * - frame count with `snip_edges = false` (`NumFrames`): `(num_samples + frame_shift / 2) / frame_shift`;
 * - frame `f` starts at `f * frame_shift + frame_shift / 2 - frame_length / 2` (`FirstSampleOfFrame`); samples outside
 *   the audio are reflected at its edges (`-1 -> 0`, `-2 -> 1`, `dim -> dim - 1`), then zero-padded to [FFT_SIZE];
 * - per frame: subtract the mean, pre-emphasize `x[i] -= 0.97 * x[i - 1]` (and `x[0] -= 0.97 * x[0]`), multiply by the
 *   povey window, FFT, power spectrum `|X[k]|^2` for `k = 0 .. FFT_SIZE / 2`, mel filterbank, floor at [LOG_FLOOR], log.
 */
fun fbank(samples: FloatArray): Array<FloatArray> {
    val frameCount = (samples.size + FRAME_SHIFT / 2) / FRAME_SHIFT
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val power = FloatArray(FFT_SIZE / 2 + 1)
    return Array(frameCount) { frame ->
        extractWindow(samples, frame, re)
        processWindow(re)
        im.fill(0f)
        FFT.transform(re, im)
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
        FloatArray(MEL_BINS) { bin -> ln(maxOf(dot(MEL_BANK[bin], power), LOG_FLOOR)) }
    }
}

/** Copies frame [frame] of [samples] into the first [FRAME_LENGTH] values of [window], reflecting at the edges; zeroes the rest. */
private fun extractWindow(
    samples: FloatArray,
    frame: Int,
    window: FloatArray,
) {
    val start = frame * FRAME_SHIFT + FRAME_SHIFT / 2 - FRAME_LENGTH / 2
    for (s in 0 until FRAME_LENGTH) {
        var index = start + s
        while (index < 0 || index >= samples.size) {
            index = if (index < 0) -index - 1 else 2 * samples.size - 1 - index
        }
        window[s] = samples[index]
    }
    window.fill(0f, FRAME_LENGTH, FFT_SIZE)
}

/** DC removal, pre-emphasis and the povey window over the first [FRAME_LENGTH] values of [window]. */
private fun processWindow(window: FloatArray) {
    var sum = 0f
    for (i in 0 until FRAME_LENGTH) sum += window[i]
    val mean = sum / FRAME_LENGTH
    for (i in 0 until FRAME_LENGTH) window[i] -= mean
    for (i in FRAME_LENGTH - 1 downTo 1) window[i] -= PREEMPHASIS * window[i - 1]
    window[0] -= PREEMPHASIS * window[0]
    for (i in 0 until FRAME_LENGTH) window[i] *= WINDOW[i]
}

private fun dot(
    a: FloatArray,
    b: FloatArray,
): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
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
