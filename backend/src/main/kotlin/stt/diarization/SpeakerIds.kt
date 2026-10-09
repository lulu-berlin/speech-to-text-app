package stt.diarization

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.Segment
import kotlin.math.sqrt

/**
 * Segments shorter than this, in whole milliseconds, are not clustered (oracle `SHORT_SEGMENT_SECONDS` = 0.5 s):
 * whisper fragments of that length are mostly silence once padded to the embedding window, so they could fabricate
 * a speaker or take a fixed-count slot.
 */
internal const val SHORT_SEGMENT_MS = 500L

/**
 * The `speakerId` (0-based) of every segment of a task, in segment order (oracle `diarize_and_write`, minus the
 * embedding and the file writing). [embeddings] holds one L2-normalized row per segment; [k] = `null` is auto mode.
 *
 * Only segments of at least [SHORT_SEGMENT_MS] are clustered, or all of them when none is that long; the others start
 * at speakerId 0 and are then rescued to the nearest speaker (see [rescueShortSegments]). Finally [summary] (by default
 * [logSpeakerSummary]) gets the clustered rows with their final speakerIds; it is diagnostics only, so a failure in it
 * is logged and never fails the labelling (oracle: `except Exception: pass`). Runs on [Dispatchers.Default]; the
 * clustering checks [gate] and cancellation between merges and reports progress to [onMergeDone] (see [upgmaMerges]).
 */
internal suspend fun speakerIds(
    segments: List<Segment>,
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
    gate: PauseGate? = null,
    summary: (clusteredEmbeddings: Array<FloatArray>, clusteredSpeakerIds: List<Int>) -> Unit = ::logSpeakerSummary,
    onMergeDone: Progress = { _, _ -> },
): List<Int> =
    withContext(Dispatchers.Default) {
        val clustered = segments.indices.filter { segments[it].isLong() }.ifEmpty { segments.indices.toList() }
        val clusteredEmbeddings = Array(clustered.size) { embeddings[clustered[it]] }
        val clusteredSpeakerIds = clusterSegments(clusteredEmbeddings, k, maxSpeakers, gate, onMergeDone)
        val speakerIds = IntArray(segments.size)
        clustered.forEachIndexed { pos, row -> speakerIds[row] = clusteredSpeakerIds[pos] }
        rescueShortSegments(segments, speakerIds, embeddings)
        try {
            summary(clusteredEmbeddings, clustered.map { speakerIds[it] })
        } catch (e: Exception) {
            speakerSummaryLogger.warn("speaker summary failed", e)
        }
        speakerIds.asList()
    }

/**
 * Gives every segment shorter than [SHORT_SEGMENT_MS] the speaker whose centroid ([speakerCentroids] of the long
 * segments) is nearest to its own embedding, any speaker, not only its neighbours in time (oracle
 * `_rescue_short_segments`): the highest dot product among the non-zero centroids (the first on ties). Without long
 * segments, or without a non-zero centroid, speakerIds stay as they are.
 */
private fun rescueShortSegments(
    segments: List<Segment>,
    speakerIds: IntArray,
    embeddings: Array<FloatArray>,
) {
    val long = segments.indices.filter { segments[it].isLong() }
    if (long.isEmpty()) return
    val centroids = speakerCentroids(long, speakerIds, embeddings)
    val nonZero = centroids.indices.filter { c -> centroids[c].any { it != 0.0 } }
    if (nonZero.isEmpty()) return
    for (row in segments.indices) {
        if (segments[row].isLong()) continue
        speakerIds[row] = nonZero.maxBy { c -> dot(embeddings[row], centroids[c]) }
    }
}

/**
 * One centroid per speaker `0..max(speakerIds)`: the L2-normalized sum, in double, of the embeddings of the [rows]
 * with that speakerId; zero when there is none or the sum is zero.
 */
private fun speakerCentroids(
    rows: List<Int>,
    speakerIds: IntArray,
    embeddings: Array<FloatArray>,
): Array<DoubleArray> {
    val centroids = Array(speakerIds.max() + 1) { DoubleArray(embeddings[0].size) }
    for (row in rows) {
        val centroid = centroids[speakerIds[row]]
        for (d in centroid.indices) centroid[d] += embeddings[row][d].toDouble()
    }
    for (centroid in centroids) {
        val norm = sqrt(centroid.sumOf { it * it })
        if (norm > 0) for (d in centroid.indices) centroid[d] /= norm
    }
    return centroids
}

/** Whether this segment is at least [SHORT_SEGMENT_MS] long. */
private fun Segment.isLong() = endMs - startMs >= SHORT_SEGMENT_MS
