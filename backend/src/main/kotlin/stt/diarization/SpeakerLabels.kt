package stt.diarization

import stt.db.Segment

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
 * Only segments of at least [SHORT_SEGMENT_MS] are clustered; the others start at label 0.
 */
internal fun speakerLabels(
    segments: List<Segment>,
    embeddings: Array<FloatArray>,
    k: Int?,
    maxSpeakers: Int,
): List<Int> {
    val clustered = segments.indices.filter { segments[it].endMs - segments[it].startMs >= SHORT_SEGMENT_MS }
    val labels = clusterSegments(Array(clustered.size) { embeddings[clustered[it]] }, k, maxSpeakers)
    val speakers = IntArray(segments.size)
    clustered.forEachIndexed { pos, row -> speakers[row] = labels[pos] }
    return speakers.asList()
}
