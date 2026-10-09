package stt.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.audio.PcmDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decodes a chunk FLAC path into deterministic 16 kHz float32 samples without reading the file:
 * `chunk-<n>.flac` becomes `<n+1>` samples of value `<n+1>`, so a test can predict the rebuilt PCM.
 */
internal class FakePcmDecoder : PcmDecoder {
    /** The source of every call, in order. */
    val decoded = mutableListOf<Path>()

    override suspend fun decode(
        source: Path,
        target: Path,
    ): Long {
        decoded.add(source)
        val n =
            source.fileName
                .toString()
                .removePrefix("chunk-")
                .removeSuffix(".flac")
                .toInt()
        val samples = n + 1
        val bytes =
            ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (i in 0 until samples) putFloat((n + 1).toFloat())
            }
        withContext(Dispatchers.IO) { Files.write(target, bytes.array()) }
        return 0L
    }
}
