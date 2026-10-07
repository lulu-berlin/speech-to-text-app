package stt.diarization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley-Tukey FFT of a fixed power-of-two [size]: `X[k] = sum_n x[n] e^(-2 pi i k n / size)`,
 * the same unnormalized forward transform as Kaldi's `SplitRadixRealFft` / kaldi-native-fbank's `Rfft`.
 * Twiddles are computed in double and stored as float. Holds no mutable state, so one instance can be shared.
 */
internal class Fft(
    private val size: Int,
) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two >= 2, was $size" }
    }

    private val cosTable = FloatArray(size / 2) { k -> cos(2 * PI * k / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { k -> sin(2 * PI * k / size).toFloat() }
    private val bitReversed = IntArray(size) { i -> Integer.reverse(i) ushr (Integer.numberOfLeadingZeros(size) + 1) }

    /** Transforms ([re], [im]), both of length [size], in place. */
    fun transform(
        re: FloatArray,
        im: FloatArray,
    ) {
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val tableStep = size / length
            for (start in 0 until size step length) {
                for (k in 0 until half) {
                    val wr = cosTable[k * tableStep]
                    val wi = -sinTable[k * tableStep]
                    val a = start + k
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            length *= 2
        }
    }
}
