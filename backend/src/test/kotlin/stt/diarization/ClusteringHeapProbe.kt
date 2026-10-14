package stt.diarization

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * The child JVM of [ClusteringHeapIntegrationSpec]: it clusters [SEGMENTS] random 512-d segments —
 * the size item 23 measured (ADR 0037) — and exits 0, or, given `matrix`, it allocates the `O(n²)`
 * distance matrix the item 09 implementation kept and exits 1 after the `OutOfMemoryError`. The
 * parent runs it with a max heap between the two footprints ([MAX_HEAP]), so *finishing* is the
 * assertion and no heap is measured in the shared test JVM.
 */
internal object ClusteringHeapProbe {
    /** The segments item 23 measured: the matrix alone is 3000² · 8 B ≈ 68.7 MiB. */
    const val SEGMENTS = 3000
    const val DIM = 512

    /** Above the clustering's footprint (embeddings + per-cluster means ≈ 30 MiB), below the matrix. */
    const val MAX_HEAP = "64m"

    private const val MATRIX = "matrix"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.firstOrNull() == MATRIX) {
                val distances = DoubleArray(SEGMENTS * SEGMENTS)
                distances[distances.lastIndex] = 1.0
                println("matrix allocated: ${distances.size}")
            } else {
                val random = Random(99L)
                val embeddings = Array(SEGMENTS) { l2Normalize(FloatArray(DIM) { random.nextFloat() * 2f - 1f }) }
                println("clustered: ${runBlocking { upgmaMerges(embeddings) }.size}")
            }
        } catch (e: OutOfMemoryError) {
            System.err.println("out of memory: ${e.message}")
            exitProcess(1)
        }
    }
}
