package stt.diarization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Cosine distance below which the last (highest) merge means one speaker in auto mode (ADR 0027). Placeholder: the
 * oracle's 0.35 (tuned for ECAPA), until it is re-tuned for CAM++ (ADR 0003).
 */
internal const val SPEAKER_DISTANCE_THRESHOLD = 0.35

/**
 * The `speakerId` (0-based) of every row of [embeddings] (L2-normalized, one per clustered segment), by average-linkage
 * (UPGMA) agglomerative clustering on cosine distance. Ported from oracle `cluster_segments`
 * (docs/oracle/transcribe.py); [k] = `null` picks the count automatically (ADR 0027), otherwise it is the fixed count,
 * clamped to `[1, n]` and not limited by [maxSpeakers]. [gate] and cancellation are checked between merges and
 * [onMergeDone] reports `(merges done, total merges)` (see [upgmaMerges]).
 */
internal suspend fun clusterSegments(
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
    gate: PauseGate? = null,
    onMergeDone: Progress = { _, _ -> },
): List<Int> {
    val n = embeddings.size
    if (n <= 1) return List(n) { 0 }
    val merges = upgmaMerges(embeddings, gate, onMergeDone)
    val speakerCount =
        when {
            k != null -> k.coerceIn(1, n)
            merges.last().height < SPEAKER_DISTANCE_THRESHOLD -> 1
            n == 2 -> 2
            else -> largestGapCount(merges, maxSpeakers)
        }
    return cutLabels(n, merges.take(n - speakerCount))
}

/**
 * Speaker count at the largest gap between consecutive merge heights, searched only among the last [maxSpeakers]
 * gaps (the top of the tree, so near-duplicate first merges cannot dominate): a cut after merge `idx` (the first
 * maximum of `heights[g + 1] - heights[g]` from `start = max(0, gaps - maxSpeakers)` on) leaves `n - (idx + 1)` clusters,
 * clamped to `[1, maxSpeakers]`: when the first gap of a full window is the largest, the cut would leave
 * `maxSpeakers + 1` clusters.
 */
private fun largestGapCount(
    merges: List<Merge>,
    maxSpeakers: Int,
): Int {
    val gaps = merges.size - 1
    val start = maxOf(0, gaps - maxSpeakers)
    var idx = start
    var largest = Double.NEGATIVE_INFINITY
    for (g in start until gaps) {
        val gap = merges[g + 1].height - merges[g].height
        if (gap > largest) {
            idx = g
            largest = gap
        }
    }
    val n = merges.size + 1
    return (n - (idx + 1)).coerceIn(1, maxSpeakers)
}

/**
 * Labels of [n] rows after applying [merges] with union-find; the roots are numbered in order of first appearance
 * by row index, so the labels are `0 until (number of clusters)`.
 */
private fun cutLabels(
    n: Int,
    merges: List<Merge>,
): List<Int> {
    val parent = IntArray(n) { it }

    fun find(x: Int): Int {
        var node = x
        while (parent[node] != node) {
            parent[node] = parent[parent[node]]
            node = parent[node]
        }
        return node
    }
    for (merge in merges) {
        val rootI = find(merge.i)
        val rootJ = find(merge.j)
        if (rootI != rootJ) parent[rootJ] = rootI
    }
    val labelOfRoot = HashMap<Int, Int>()
    return List(n) { row -> labelOfRoot.getOrPut(find(row)) { labelOfRoot.size } }
}

/**
 * The `n - 1` average-linkage merges of [embeddings] in merge order (oracle `cluster_segments` merge loop).
 *
 * `D = 1 - E·Eᵀ` in double with `+∞` on the diagonal. Each step merges the first minimum of `D` in row-major order
 * over the active clusters (kept in original index order, so `i < j`); the merged row is the size-weighted mean
 * `(size_i·D[i,·] + size_j·D[j,·]) / (size_i + size_j)`, so every segment counts once; cluster `j` becomes inactive.
 * `O(n²)` memory, `O(n³)` time.
 *
 * Before every merge, cancellation of this coroutine and [gate] (ADR 0004) are checked, so a long recording pauses or
 * stops at the next merge; [onMergeDone] reports `(merges done, n - 1)` after each merge.
 */
internal suspend fun upgmaMerges(
    embeddings: Array<FloatArray>,
    gate: PauseGate? = null,
    onMergeDone: Progress = { _, _ -> },
): List<Merge> {
    val n = embeddings.size
    val d = DoubleArray(n * n)
    for (a in 0 until n) {
        d[a * n + a] = Double.POSITIVE_INFINITY
        for (b in a + 1 until n) {
            val distance = 1.0 - dot(embeddings[a], embeddings[b])
            d[a * n + b] = distance
            d[b * n + a] = distance
        }
    }
    val size = IntArray(n) { 1 }
    val active = MutableList(n) { it }
    val merges = ArrayList<Merge>(maxOf(n - 1, 0))
    while (active.size > 1) {
        currentCoroutineContext().ensureActive()
        gate?.checkpoint()
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
        onMergeDone(merges.size, n - 1)
    }
    return merges
}
