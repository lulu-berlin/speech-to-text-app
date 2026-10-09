package stt.diarization

import stt.db.Segment
import kotlin.math.sqrt

/**
 * Segments shorter than this, in whole milliseconds, are not clustered (oracle `SHORT_SEGMENT_SECONDS` = 0.5 s):
 * whisper fragments of that length are mostly silence once padded to the embedding window, so they could fabricate
 * a speaker or take a fixed-count slot.
 */
internal const val SHORT_SEGMENT_MS = 500L

/**
 * Speaker label (0-based) per segment of a task, in segment order (oracle `diarize_and_write`, minus the embedding
 * and the file writing). [embeddings] holds one L2-normalized row per segment; [k] = `null` is auto mode.
 *
 * Only segments of at least [SHORT_SEGMENT_MS] are clustered; the others start at label 0 and are then rescued to the
 * nearest speaker (see [rescueShortSegments]).
 */
internal fun speakerLabels(
    segments: List<Segment>,
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
): List<Int> {
    val clustered = segments.indices.filter { segments[it].isLong() }
    val labels = clusterSegments(Array(clustered.size) { embeddings[clustered[it]] }, k, maxSpeakers)
    val speakers = IntArray(segments.size)
    clustered.forEachIndexed { pos, row -> speakers[row] = labels[pos] }
    rescueShortSegments(segments, speakers, embeddings)
    return speakers.asList()
}

/**
 * Gives every segment shorter than [SHORT_SEGMENT_MS] the speaker whose centroid is nearest to its own embedding, any
 * speaker, not only its neighbours in time (oracle `_rescue_short_segments`). A centroid is the L2-normalized sum of the
 * embeddings of that speaker's long segments, or zero without any; the nearest is the highest dot product among the
 * non-zero centroids (the first on ties). Without long segments, or without a non-zero centroid, labels stay as they are.
 */
private fun rescueShortSegments(
    segments: List<Segment>,
    speakers: IntArray,
    embeddings: Array<FloatArray>,
) {
    val long = segments.indices.filter { segments[it].isLong() }
    if (long.isEmpty()) return
    val centroids = Array(speakers.max() + 1) { DoubleArray(embeddings[0].size) }
    for (row in long) {
        val centroid = centroids[speakers[row]]
        for (d in centroid.indices) centroid[d] += embeddings[row][d].toDouble()
    }
    val nonZero = centroids.indices.filter { c -> centroids[c].any { it != 0.0 } }
    if (nonZero.isEmpty()) return
    for (centroid in centroids) {
        val norm = sqrt(centroid.sumOf { it * it })
        if (norm > 0) for (d in centroid.indices) centroid[d] /= norm
    }
    for (row in segments.indices) {
        if (segments[row].isLong()) continue
        speakers[row] = nonZero.maxBy { c -> dot(embeddings[row], centroids[c]) }
    }
}

private fun dot(
    a: FloatArray,
    b: DoubleArray,
): Double {
    var sum = 0.0
    for (d in a.indices) sum += a[d] * b[d]
    return sum
}

/** Whether this segment is at least [SHORT_SEGMENT_MS] long. */
private fun Segment.isLong() = endMs - startMs >= SHORT_SEGMENT_MS
