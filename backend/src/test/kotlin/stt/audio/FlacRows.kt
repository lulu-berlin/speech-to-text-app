package stt.audio

import stt.db.CanonicalAudio

/**
 * A canonical FLAC in memory, split into its header (the `fLaC` marker and the metadata blocks, everything before
 * the first audio frame) and copies of its frames in order: the header plus the frames is the encoded file byte for
 * byte. Test-side form of [FlacLayout], for building clips and malformed streams.
 */
data class SplitFlac(
    val header: ByteArray,
    val frames: List<ByteArray>,
) {
    /** The clip's sample count, from STREAMINFO ([streamInfoTotalSamples]). */
    val totalSamples: Long
        get() = streamInfoTotalSamples(header)
}

/** Splits an in-memory FLAC into its header and copies of its frames, as [FlacSplitter.layout] finds them. */
internal fun FlacSplitter.split(flac: ByteArray): SplitFlac {
    val layout = layout(ArrayFlacBytes(flac))
    return SplitFlac(layout.header, (0 until layout.frameCount).map { flac.copyOfRange(layout.frameStart(it), layout.frameEnd(it)) })
}

/** One stored row of a canonical clip: the [frames] of second [second], up to four 250 ms frames (item 44). */
data class FlacRow(
    val second: Int,
    val frames: List<ByteArray>,
) {
    fun joinToBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        frames.forEach(out::write)
        return out.toByteArray()
    }
}

/** A canonical clip split into its header and one row per second of up to four frames; only the last row is partial (item 44). */
data class FlacRows(
    val header: ByteArray,
    val rows: List<FlacRow>,
) {
    companion object {
        /** Groups a split canonical FLAC's frames into one row per second (the last row takes what is left). */
        fun from(flac: SplitFlac): FlacRows =
            FlacRows(
                header = flac.header,
                rows =
                    flac.frames
                        .chunked(FRAMES_PER_ROW)
                        .mapIndexed { second, frames -> FlacRow(second, frames) },
            )
    }
}

/** The rows a canonical clip is stored as (item 44): one [CanonicalAudio] per second with its frame count. */
fun FlacRows.toCanonicalRows(): List<CanonicalAudio> = rows.map { row -> CanonicalAudio(row.second, row.frames.size, row.joinToBytes()) }
