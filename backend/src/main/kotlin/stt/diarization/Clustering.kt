package stt.diarization

/**
 * Cosine distance below which the last (highest) merge means one speaker in auto mode (ADR 0027). Placeholder: the
 * oracle's 0.35 (tuned for ECAPA), until it is re-tuned for CAM++ (ADR 0003).
 */
internal const val SPEAKER_DISTANCE_THRESHOLD = 0.35

/**
 * Speaker label (0-based) per row of [embeddings] (L2-normalized, one per clustered segment), by average-linkage
 * (UPGMA) agglomerative clustering on cosine distance. Ported from oracle `cluster_segments`
 * (docs/oracle/transcribe.py); [k] = `null` picks the count automatically (ADR 0027), otherwise it is the fixed count.
 */
internal fun clusterSegments(
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
): List<Int> {
    val n = embeddings.size
    if (n <= 1) return List(n) { 0 }
    if (n > 2 || k != null) TODO("three or more segments and fixed counts: next test-list items")
    val height = cosineDistance(embeddings[0], embeddings[1])
    return if (height < SPEAKER_DISTANCE_THRESHOLD) listOf(0, 0) else listOf(0, 1)
}

/** `1 - a·b` in double: the cosine distance of two L2-normalized rows. */
private fun cosineDistance(
    a: FloatArray,
    b: FloatArray,
): Double {
    var dot = 0.0
    for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
    return 1.0 - dot
}
