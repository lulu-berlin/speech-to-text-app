package stt.diarization

/** [pcm] as the diarizer's source (item 43): an in-memory clip, for specs that hold the samples themselves. */
internal fun pcmSource(pcm: FloatArray): PcmSource =
    object : PcmSource {
        override val totalSamples = pcm.size

        override suspend fun samples(
            fromSample: Int,
            toSample: Int,
        ): FloatArray = pcm.copyOfRange(fromSample, toSample)
    }
