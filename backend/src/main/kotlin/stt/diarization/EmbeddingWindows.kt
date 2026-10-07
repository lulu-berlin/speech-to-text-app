package stt.diarization

/** 2 s at 16 kHz: minimum analysis window, zero-padded with the segment centered (oracle MIN_EMBED_SECONDS). */
internal const val SHORT_WINDOW_SAMPLES = 32_000

/** 20 s at 16 kHz: longer segments use a centered slice of this length (oracle MAX_EMBED_SECONDS). */
internal const val CAP_WINDOW_SAMPLES = 320_000

/**
 * The embedding window of one segment over the whole-file mono 16 kHz [pcm], ported 1:1 from the oracle's
 * `embed_windows` (docs/oracle/transcribe.py): timestamps in ms become sample indices as `ms * 16`
 * (`int(seconds * 16000)` with whole ms), and each branch of the oracle is reproduced:
 * - past the end or empty segment: the last [SHORT_WINDOW_SAMPLES] samples (or all of them);
 * - still empty after clipping: an all-zero [SHORT_WINDOW_SAMPLES] window;
 * - shorter than [SHORT_WINDOW_SAMPLES]: a zero buffer with the segment's audio centered, never borrowing
 *   neighbouring audio;
 * - longer than [CAP_WINDOW_SAMPLES]: the centered [CAP_WINDOW_SAMPLES] slice;
 * - otherwise the segment's samples as they are.
 * Returns the (possibly padded) samples; a segment < 0.5 s is still embedded (its vector may rescue it, item 09).
 */
internal fun embeddingWindow(
    pcm: FloatArray,
    startMs: Long,
    endMs: Long,
): FloatArray {
    val total = pcm.size
    var t0 = msToSamples(startMs)
    var t1 = msToSamples(endMs)
    if (t1 <= t0 || t0 >= total) { // (near-)past the end of the audio: use the tail
        t0 = maxOf(0, total - SHORT_WINDOW_SAMPLES)
        t1 = total
    }
    val dur = minOf(t1, total) - t0
    if (dur <= 0) return FloatArray(SHORT_WINDOW_SAMPLES)
    if (dur < SHORT_WINDOW_SAMPLES) { // zero-pad to the minimum analysis length, centered
        val window = FloatArray(SHORT_WINDOW_SAMPLES)
        val off = (SHORT_WINDOW_SAMPLES - dur) / 2
        pcm.copyInto(window, off, t0, t0 + dur)
        return window
    }
    if (dur > CAP_WINDOW_SAMPLES) { // center-capped long window
        val mid = (t0 + t1) / 2
        val st = maxOf(t0, mid - CAP_WINDOW_SAMPLES / 2)
        val en = minOf(t1, st + CAP_WINDOW_SAMPLES)
        return pcm.copyOfRange(st, en)
    }
    return pcm.copyOfRange(t0, t0 + dur)
}

/** Whole milliseconds to samples at 16 kHz: `int(seconds * 16000)` = `ms * 16` exactly. */
private fun msToSamples(ms: Long): Int = (ms * 16).toInt()
