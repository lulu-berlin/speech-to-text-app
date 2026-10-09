package stt.diarization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The item 09 `O(n³)` implementation of [upgmaMerges] and [clusterSegments], kept in test sources as the reference for
 * the nearest-neighbor-chain differential tests (roadmap item 23): it must stay the 1:1 port of `docs/oracle/transcribe.py`
 * so that it never drifts from the behavior the chain is compared against.

 * [naiveUpgmaMerges] matches [upgmaMerges]'s contract exactly (bridge suspend, gate, cancellation,and progress
 * between merges);[naiveClusterSegments] matches [clusterSegments]'s labels for the same inputs. Neither takes the
 * progress callbacks:the differential tests compare merges and labels directly, so no pausing is involved.

 * Both are byte-for-byte the pre-switch bodies, renamed only.
 */
internal suspend fun naiveUpgmaMerges(
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

/** Speaker count rule of [clusterSegments], copied verbatim from the production implementation. */
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

/** Labels of [n] rows after applying [merges] — copied verbatim from the production implementation. */
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
 * Labels on [embeddings] by the item 09 implementation of [clusterSegments]:naive reference for the
 * label-level differential test. Matches [clusterSegments]'s contract ([k] = `null` is auto mode;the speaker
 * threshold, ADR 0027 and the largest-gap rule all come from the copied code above).
 */
internal suspend fun naiveClusterSegments(
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
): List<Int> {
    val n = embeddings.size
    if (n <= 1) return List(n) { 0 }
    val merges = naiveUpgmaMerges(embeddings)
    val speakerCount =
        when {
            k != null -> k.coerceIn(1, n)
            merges.last().height < SPEAKER_DISTANCE_THRESHOLD -> 1
            n == 2 -> 2
            else -> largestGapCount(merges, maxSpeakers)
        }
    return cutLabels(n, merges.take(n - speakerCount))
}
