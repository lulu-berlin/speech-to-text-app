package stt.diarization

/**
 * The audio a diarization reads its speaker windows from (item 43): one task's clip by global sample range, so no
 * whole-clip `FloatArray` is ever materialized. [totalSamples] is the clip's sample count, which the windowing
 * needs for its past-the-end branch; `samples(from, to)` reads the clip's samples
 * `[fromSample, toSample)` — one segment's window, or the slice of one it needs — with global positions, so
 * windows keep the timestamps they had over a whole-clip PCM. The engine implements it over the stored canonical
 * clip (`stt.engine.ClipPcmSource`); tests use an in-memory one over a `FloatArray`.
 */
internal interface PcmSource {
    /** The clip's sample count. */
    val totalSamples: Int

    /**
     * The clip's samples `[fromSample, toSample)`, in order. Requires `0 <= fromSample <= toSample <= totalSamples`;
     * an empty range reads nothing.
     */
    suspend fun samples(
        fromSample: Int,
        toSample: Int,
    ): FloatArray
}
