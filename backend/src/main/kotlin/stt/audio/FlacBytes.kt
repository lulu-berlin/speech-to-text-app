package stt.audio

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The bytes of a FLAC stream as [FlacSplitter] walks them (item 71): read in place by position, so a clip on disk
 * is never copied into the heap whole. Positions are `Int`, as they were for the in-memory clip this replaces.
 */
internal interface FlacBytes {
    val size: Int

    operator fun get(index: Int): Byte

    /** A copy of the bytes in `[from, to)`. */
    fun copy(
        from: Int,
        to: Int,
    ): ByteArray
}

/** A FLAC stream already in memory. */
internal class ArrayFlacBytes(
    private val bytes: ByteArray,
) : FlacBytes {
    override val size: Int get() = bytes.size

    override fun get(index: Int): Byte = bytes[index]

    override fun copy(
        from: Int,
        to: Int,
    ): ByteArray = bytes.copyOfRange(from, to)
}

/**
 * A FLAC file read through [channel], which the caller opens and closes: single bytes come from one cached
 * [BLOCK_BYTES] block (the splitter reads forward, revisiting at most the frame it is in), and [copy] reads its
 * range straight from the file. Blocking reads: callers run on `Dispatchers.IO`.
 */
internal class FileFlacBytes(
    private val channel: FileChannel,
) : FlacBytes {
    override val size: Int =
        channel.size().let { size ->
            require(size <= Int.MAX_VALUE) { "the clip has $size bytes, more than ${Int.MAX_VALUE}" }
            size.toInt()
        }

    private val block = ByteArray(BLOCK_BYTES)
    private var blockStart = 0
    private var blockLength = 0

    override fun get(index: Int): Byte {
        if (index < blockStart || index >= blockStart + blockLength) loadBlockOf(index)
        return block[index - blockStart]
    }

    /** Reads the [BLOCK_BYTES]-aligned block that holds byte [index] into [block]. */
    private fun loadBlockOf(index: Int) {
        if (index !in 0 until size) throw IndexOutOfBoundsException("byte $index of $size")
        blockStart = index - index % BLOCK_BYTES
        blockLength = minOf(BLOCK_BYTES, size - blockStart)
        readFully(ByteBuffer.wrap(block, 0, blockLength), blockStart)
    }

    override fun copy(
        from: Int,
        to: Int,
    ): ByteArray = ByteArray(to - from).also { readFully(ByteBuffer.wrap(it), from) }

    private fun readFully(
        target: ByteBuffer,
        at: Int,
    ) {
        var position = at.toLong()
        while (target.hasRemaining()) {
            val read = channel.read(target, position)
            if (read < 0) throw IndexOutOfBoundsException("the clip ended at byte $position, before byte ${at + target.limit()}")
            position += read
        }
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
    }
}
