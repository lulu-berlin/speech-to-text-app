package stt.audio

/**
 * A stored canonical FLAC, split into its header (the `fLaC` marker and the metadata blocks, everything before
 * the first audio frame) and its frames in order. The header plus the frames is the encoded file byte for byte.
 */
data class SplitFlac(
    val header: ByteArray,
    val frames: List<ByteArray>,
) {
    /**
     * The clip's sample count, from STREAMINFO — the header's first block, which FLAC requires: its 36-bit total at
     * bit 108 of the block's data (after the 4-byte marker and the 4-byte block header). The canonical encode writes
     * to a file, so the count is filled in (a pipe would leave it 0).
     */
    val totalSamples: Long
        get() {
            fun byte(i: Int) = header[i].toLong() and 0xFF
            return ((byte(21) and 0x0F) shl 32) or (byte(22) shl 24) or (byte(23) shl 16) or (byte(24) shl 8) or byte(25)
        }
}

/**
 * Splits a 16 kHz mono FLAC (item 44's canonical format) into its header and its frames. The app's own encoder
 * makes every frame exactly 4000 samples except the last, which holds the remainder (ffmpeg does not pad it), and
 * numbers them consecutively from 0. The splitter walks the frames from the end of the metadata: each must start
 * where the previous one ended, with its sync code, header CRC-8 and the next frame number; it ends where its
 * CRC-16 closes and the next frame starts, or at the end of the file. Only the last frame may be shorter than
 * 4000 samples, and the frames must hold exactly the sample count STREAMINFO declares. Any input that is not
 * exactly that layout is refused.
 */
object FlacSplitter {
    private const val FRAME_SAMPLES = 4000

    /** The header byte just past STREAMINFO's total sample count ([SplitFlac.totalSamples] reads bytes 21–25). */
    private const val STREAMINFO_TOTAL_END = 26

    /** Thrown when [flac] is not the canonical frame layout, so it cannot be stored as a canonical clip. */
    class NotCanonical(
        message: String,
    ) : Exception(message)

    fun split(flac: ByteArray): SplitFlac {
        require(
            flac.size >= 4 && flac[0] == 0x66.toByte() && flac[1] == 0x4C.toByte() && flac[2] == 0x61.toByte() && flac[3] == 0x43.toByte(),
        ) {
            "not a FLAC file"
        }
        // The audio data starts after the metadata blocks (the first byte after 'fLaC' carries the flags).
        val audioStart = metadataEnd(flac)

        // Every candidate frame start. Coincidental sync patterns inside a frame's data are common (the audio
        // bytes are arbitrary), so a candidate only ends a frame when the frame's CRC-16 closes there and the
        // candidate is itself the next frame's header (its CRC-8 and number).
        val candidates = mutableListOf<Int>()
        var scan = audioStart
        while (true) {
            val sync = findSync(flac, scan) ?: break
            candidates.add(sync)
            scan = sync + 2
        }

        val frames = mutableListOf<ByteArray>()
        var sampleCount = 0L
        var start = audioStart
        var next = 0
        while (start < flac.size) {
            val number = frames.size
            val header = frameHeaderAt(flac, start)
            if (header == null || !header.startsFrame(number)) throw NotCanonical("no frame $number at byte $start")
            val samples = header.blocksize
            if (samples > FRAME_SAMPLES) throw NotCanonical("frame $number has $samples samples, more than $FRAME_SAMPLES")
            while (next < candidates.size && candidates[next] <= start) next += 1
            var end = flac.size
            // A sync pair the frame closes on but with no room for a header behind it: the next frame is cut off —
            // unless the frame really runs to the end of the stream, which the check below settles.
            var cutOff: Int? = null
            for (i in next until candidates.size) {
                val candidate = candidates[i]
                if (!crc16(flac, start, candidate)) continue
                val following = frameHeaderAt(flac, candidate)
                if (following == null) {
                    cutOff = cutOff ?: candidate
                    continue
                }
                if (following.startsFrame(number + 1)) {
                    end = candidate
                    break
                }
            }
            if (end == flac.size && !crc16(flac, start, end)) {
                throw NotCanonical(
                    cutOff?.let { "frame ${number + 1} at byte $it is truncated" } ?: "no valid boundary after the frame at $start",
                )
            }
            if (samples != FRAME_SAMPLES && end != flac.size) {
                throw NotCanonical("frame $number has $samples samples but is not the last")
            }
            frames.add(flac.copyOfRange(start, end))
            sampleCount += samples
            start = end
        }
        if (frames.isEmpty()) throw NotCanonical("no frames found after the header")
        // A frame the walk did not recognize would be folded into the one before it (a CRC-16 over two whole frames
        // closes where the second one's does); the frames then hold fewer samples than STREAMINFO declares.
        if (audioStart < STREAMINFO_TOTAL_END) throw NotCanonical("the header is too short for STREAMINFO")
        val split = SplitFlac(flac.copyOfRange(0, audioStart), frames)
        if (sampleCount != split.totalSamples) {
            throw NotCanonical("the frames hold $sampleCount samples, but STREAMINFO declares ${split.totalSamples}")
        }
        return split
    }

    /**
     * The frame header at [at], or null when its bytes do not fit in [flac]: its first five bytes give its length (the
     * codes and the coded number's leading byte), then the whole header that length implies must fit too.
     */
    private fun frameHeaderAt(
        flac: ByteArray,
        at: Int,
    ): FrameHeader? {
        if (at + 4 >= flac.size) return null
        val header = FrameHeader(flac, at)
        return if (at + header.length <= flac.size) header else null
    }

    /**
     * A frame header at [at] in [flac], in the reader's field order (as libFLAC reads it): sync (2), the
     * frame-header byte with the block-size and sample-rate codes, the channel-assignment/sample-size byte, the
     * UTF-8-coded number, the block-size field (1 byte for code 6, 2 for code 7, none for the standard sizes the other
     * codes name), the sample-rate field (0–2 bytes by its code), and the
     * header CRC-8 (1). Only [frameHeaderAt] makes one, so every field lies within [flac].
     */
    private class FrameHeader(
        private val flac: ByteArray,
        private val at: Int,
    ) {
        private val blockSizeCode = (flac[at + 2].toInt() ushr 4) and 0x0F
        private val numberLength = utf8Length(flac, at + 4)

        /** The header's length in bytes, its CRC-8 included. */
        val length: Int =
            4 + numberLength +
                when (blockSizeCode) {
                    6 -> 1
                    7 -> 2
                    else -> 0
                } +
                when (flac[at + 2].toInt() and 0x0F) {
                    12 -> 1
                    13, 14 -> 2
                    else -> 0
                } + 1

        /**
         * The frame number, UTF-8-coded at [at] + 4: a one-byte number is the leading byte's low 7 bits, and a longer
         * one keeps the leading byte's remaining (7 − length) bits followed by 6 bits per byte.
         */
        val number: Int
            get() {
                val first = flac[at + 4].toInt() and 0xFF
                if (first and 0x80 == 0) return first
                var value = first and (0x7F shr numberLength)
                for (i in 1 until numberLength) {
                    value = (value shl 6) or (flac[at + 4 + i].toInt() and 0x3F)
                }
                return value
            }

        /** The frame's block size: the STREAMINFO value for codes 0–5 (256 << code for 8–15), or the coded value + 1 for 6/7. */
        val blocksize: Int
            get() {
                val field = at + 4 + numberLength
                return when (blockSizeCode) {
                    1 -> 192
                    2, 3, 4, 5 -> 576 shl (blockSizeCode - 2)
                    6 -> (flac[field].toInt() and 0xFF) + 1
                    7 -> (((flac[field].toInt() and 0xFF) shl 8) or (flac[field + 1].toInt() and 0xFF)) + 1
                    8, 9, 10, 11, 12, 13, 14, 15 -> 256 shl (blockSizeCode - 8)
                    else -> throw NotCanonical("unknown blocksize code $blockSizeCode")
                }
            }

        /** Whether this header starts frame [number]: its sync code, a matching CRC-8 and that number. */
        fun startsFrame(number: Int): Boolean =
            (flac[at].toInt() and 0xFF) == 0xFF &&
                (flac[at + 1].toInt() and 0xFE) == 0xF8 &&
                crc8(flac, at, at + length - 1) == (flac[at + length - 1].toInt() and 0xFF) &&
                this.number == number
    }

    private fun metadataEnd(flac: ByteArray): Int {
        var p = 4
        while (true) {
            if (p + 4 > flac.size) throw NotCanonical("truncated metadata")
            val last = flac[p].toInt() and 0x80 != 0
            val length =
                ((flac[p + 1].toInt() and 0xFF) shl 16) or
                    ((flac[p + 2].toInt() and 0xFF) shl 8) or
                    (flac[p + 3].toInt() and 0xFF)
            p += 4 + length
            if (last) return p
        }
    }

    /** The next frame sync (0xFF 0xF8/0xF9) at or after [from], or null. */
    private fun findSync(
        flac: ByteArray,
        from: Int,
    ): Int? {
        var i = from
        while (i < flac.size - 1) {
            if ((flac[i].toInt() and 0xFF) == 0xFF && (flac[i + 1].toInt() and 0xFE) == 0xF8) return i
            i += 1
        }
        return null
    }

    /**
     * The length of the UTF-8-coded frame number that starts at [p], by its leading byte's prefix: `0xxxxxxx` is
     * one byte, `110xxxxx` two, `1110xxxx` three, and so on (frame numbers stay well under 2^21).
     */
    private fun utf8Length(
        flac: ByteArray,
        p: Int,
    ): Int {
        val first = flac[p].toInt() and 0xFF
        if (first and 0x80 == 0) return 1
        var length = 0
        var mask = 0x80
        while (first and mask != 0) {
            length += 1
            mask = mask shr 1
        }
        return length
    }

    private fun crc8(
        data: ByteArray,
        from: Int,
        toExclusive: Int,
    ): Int {
        var crc = 0
        for (i in from until toExclusive) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /**
     * FLAC's frame CRC-16 over the whole frame — sync bytes, the frame header with its CRC-8, and the subframes —
     * checked against the frame's last two bytes, which [toExclusive] points just past. The polynomial is 0x8005,
     * MSB-first, seeded with 0 (libFLAC's `FLAC__CRC16_UPDATE` over the frame, footer excluded).
     */
    private fun crc16(
        flac: ByteArray,
        from: Int,
        toExclusive: Int,
    ): Boolean {
        var crc = 0
        for (i in from until toExclusive - 2) {
            crc = crc xor ((flac[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        val stored = ((flac[toExclusive - 2].toInt() and 0xFF) shl 8) or (flac[toExclusive - 1].toInt() and 0xFF)
        return stored == crc
    }
}
