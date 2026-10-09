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
    val lastHeight = upgmaMerges(embeddings).last().height
    return if (lastHeight < SPEAKER_DISTANCE_THRESHOLD) listOf(0, 0) else listOf(0, 1)
}

/**
 * The `n - 1` average-linkage merges of [embeddings] in merge order (oracle `cluster_segments` merge loop).
 *
 * `D = 1 - E·Eᵀ` in double with `+∞` on the diagonal. Each step merges the first minimum of `D` in row-major order
 * over the active clusters (kept in original index order, so `i < j`); the merged row is the size-weighted mean
 * `(size_i·D[i,·] + size_j·D[j,·]) / (size_i + size_j)`, so every segment counts once; cluster `j` becomes inactive.
 * `O(n²)` memory, `O(n³)` time.
 */
internal fun upgmaMerges(embeddings: Array<FloatArray>): List<Merge> {
    val n = embeddings.size
    val d = DoubleArray(n * n)
    for (a in 0 until n) {
        d[a * n + a] = Double.POSITIVE_INFINITY
        for (b in a + 1 until n) {
            val distance = cosineDistance(embeddings[a], embeddings[b])
            d[a * n + b] = distance
            d[b * n + a] = distance
        }
    }
    val size = IntArray(n) { 1 }
    val active = MutableList(n) { it }
    val merges = ArrayList<Merge>(maxOf(n - 1, 0))
    while (active.size > 1) {
        // D is symmetric with +∞ on the diagonal, so the first row-major minimum lies above the diagonal.
        var i = -1
        var j = -1
        var best = Double.POSITIVE_INFINITY
        for (ai in 0 until active.size - 1) {
            val row = active[ai] * n
            for (bi in ai + 1 until active.size) {
                val value = d[row + active[bi]]
                if (i < 0 || value < best) {
                    i = active[ai]
                    j = active[bi]
                    best = value
                }
            }
        }
        merges.add(Merge(i, j, best))
        val sizeI = size[i]
        val sizeJ = size[j]
        for (x in active) {
            if (x == i || x == j) continue
            val merged = (sizeI * d[i * n + x] + sizeJ * d[j * n + x]) / (sizeI + sizeJ)
            d[i * n + x] = merged
            d[x * n + i] = merged
        }
        size[i] = sizeI + sizeJ
        active.remove(j)
    }
    return merges
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
