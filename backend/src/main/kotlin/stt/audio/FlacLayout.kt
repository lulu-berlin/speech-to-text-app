package stt.audio

import stt.db.CanonicalAudio

/** A one-second row of the canonical clip (item 44): four 4000-sample frames; only the last row may hold fewer. */
internal const val FRAMES_PER_ROW = 4

/**
 * Where a canonical FLAC's parts lie, as [FlacSplitter.layout] found them (item 71): the [header] bytes, and the
 * offset just past each frame — the frames themselves stay in the stream, frame 0 starting where the header ends.
 */
internal class FlacLayout(
    val header: ByteArray,
    private val frameEnds: IntArray,
) {
    /** The clip's sample count, from STREAMINFO ([streamInfoTotalSamples]). */
    val totalSamples: Long
        get() = streamInfoTotalSamples(header)

    val frameCount: Int
        get() = frameEnds.size

    fun frameStart(frame: Int): Int = if (frame == 0) header.size else frameEnds[frame - 1]

    fun frameEnd(frame: Int): Int = frameEnds[frame]

    /**
     * The clip's rows as `audio_second` stores them, read from [bytes] — the stream this layout was found in — one row
     * at a time, as the sequence reaches it: row k holds frames 4k to 4k + 3, the last row what is left.
     */
    fun rows(bytes: FlacBytes): Sequence<CanonicalAudio> =
        (0 until (frameCount + FRAMES_PER_ROW - 1) / FRAMES_PER_ROW).asSequence().map { second ->
            val first = second * FRAMES_PER_ROW
            val last = minOf(first + FRAMES_PER_ROW, frameCount) - 1
            CanonicalAudio(second, frames = last - first + 1, bytes = bytes.copy(frameStart(first), frameEnd(last)))
        }
}
