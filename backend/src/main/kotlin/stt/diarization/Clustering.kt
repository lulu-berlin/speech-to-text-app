package stt.diarization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Cosine distance below which the last (highest) merge means one speaker in auto mode (ADR 0027): 0.5448, measured
 * for CAM++ on 18 LibriSpeech clips of 6 readers as the value with the fewest misclassified pairs (ADR 0032).
 */
internal const val SPEAKER_DISTANCE_THRESHOLD = 0.5448

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
 * The `n - 1` average-linkage merges of [embeddings] in merge order (oracle `cluster_segments` merge loop), found by the
 * nearest-neighbor chain (ADR 0037).
 *
 * Average linkage is reducible, so the chain finds the same merges: a chain of clusters is built by repeatedly appending
 * the nearest active cluster of the chain's end; when the end and its predecessor are mutual nearest neighbors they are
 * merged (a restart if not), and the chain continues from the merged cluster. Distances are computed on demand in double:
 * each cluster keeps the mean of its members' embeddings and `d(A, B) = mean_{a∈A, b∈B} (1 − a·b) = 1 − vA·vB`
 * (bilinearity), so no `n²` distance matrix is allocated — `O(n·512)` memory (ADR 0037: 14 MiB vs 71 MiB live heap at
 * 3000 segments, 36.1 s vs 50.5 s at 5000).
 *
 * The chain's merges have non-decreasing heights; they are sorted by height (stable), which reproduces the item 09 merge
 * order. Where two different merges have exactly equal heights the pair at each position may differ — accepted and
 * documented (ADR 0037); the ADR 0027 and largest-gap rules read the heights, which are unchanged apart from ulp-level
 * rounding and tie-run internal order.
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
    if (n <= 1) return emptyList()

    val mean = Array(n) { row -> DoubleArray(embeddings[row].size) { embeddings[row][it].toDouble() } }
    val size = IntArray(n) { 1 }
    val active = MutableList(n) { it }

    fun distance(
        a: Int,
        b: Int,
    ): Double {
        var dot = 0.0
        for (k in mean[a].indices) dot += mean[a][k] * mean[b][k]
        return 1.0 - dot
    }

    /** The nearest active cluster of [x] and its distance: the first minimum over the active list, so the chain
     *  is deterministic for a given input. */
    fun nearestOf(x: Int): Pair<Int, Double> {
        var nn = -1
        var best = Double.POSITIVE_INFINITY
        for (y in active) {
            if (y == x) continue
            val dist = distance(x, y)
            if (nn < 0 || dist < best) {
                nn = y
                best = dist
            }
        }
        return nn to best
    }

    val merges = ArrayList<Merge>(maxOf(n - 1, 0))
    val chain = ArrayList<Int>()
    while (active.size > 1) {
        if (chain.isEmpty()) chain.add(active[0])
        val c = chain.last()
        val (nn, best) = nearestOf(c)
        if (chain.size >= 2 && chain[chain.size - 2] == nn) {
            val p = chain.last()
            val q = chain[chain.size - 2]
            // A merge needs mutual nearest neighbors (the global closest pair is always mutual); restart otherwise.
            val (back, _) = nearestOf(q)
            if (back != p) {
                chain.clear()
                continue
            }
            currentCoroutineContext().ensureActive()
            gate?.checkpoint()
            chain.removeLast()
            chain.removeLast()
            val i = minOf(p, q)
            val j = maxOf(p, q)
            merges.add(Merge(i, j, best))
            val sizeI = size[i]
            val sizeJ = size[j]
            for (k in mean[i].indices) mean[i][k] = (sizeI * mean[i][k] + sizeJ * mean[j][k]) / (sizeI + sizeJ)
            size[i] = sizeI + sizeJ
            active.remove(j)
            chain.add(i)
            onMergeDone(merges.size, n - 1)
        } else if (chain.size < n) {
            chain.add(nn)
        } else {
            chain.clear()
        }
    }
    return merges.sortedWith(compareBy(Merge::height))
}
