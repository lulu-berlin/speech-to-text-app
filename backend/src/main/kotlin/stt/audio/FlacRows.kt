package stt.audio

import stt.db.CanonicalAudio

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
                        .chunked(4)
                        .mapIndexed { second, frames -> FlacRow(second, frames) },
            )
    }
}

/** The rows a canonical clip is stored as (item 44): one [CanonicalAudio] per second with its frame count. */
fun FlacRows.toCanonicalRows(): List<CanonicalAudio> = rows.map { row -> CanonicalAudio(row.second, row.frames.size, row.joinToBytes()) }
