package stt.diarization

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
    TODO("clustering of two or more segments: next test-list items")
}
