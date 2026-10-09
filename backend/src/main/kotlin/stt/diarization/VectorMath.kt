package stt.diarization

import kotlin.math.sqrt

/** `a·b` over the indices of [a], accumulated in float in index order (Fbank's Kaldi-matching precision). */
internal fun dotInFloat(
    a: FloatArray,
    b: FloatArray,
): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

/** `a·b` over the indices of [a], each product and the sum in double. */
internal fun dot(
    a: FloatArray,
    b: FloatArray,
): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
    return sum
}

/** `a·b` over the indices of [a], in double. */
internal fun dot(
    a: FloatArray,
    b: DoubleArray,
): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

/**
 * L2-normalizes [vector] in place; a zero vector (no energy) stays all zeros, as the oracle sets the norm to 1
 * only when it is non-zero (`embed_windows`: `norms[norms == 0] = 1.0`).
 */
internal fun l2Normalize(vector: FloatArray): FloatArray {
    var sumSquares = 0.0
    for (value in vector) sumSquares += value.toDouble() * value.toDouble()
    val norm = sqrt(sumSquares)
    if (norm == 0.0) return vector
    for (i in vector.indices) vector[i] = (vector[i].toDouble() / norm).toFloat()
    return vector
}
