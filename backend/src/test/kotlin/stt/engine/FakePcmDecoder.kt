package stt.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.audio.PcmDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decodes a FLAC path into deterministic 16 kHz float32 samples without reading the file: `chunk-<n>.flac`
 * becomes `<n+1>` samples of value `<n+1>` (the old per-chunk reconstruction's shape), and anything else — a
 * canonical clip materialized from the database (item 44) — becomes [clipPcm], so a test can predict both
 * what the engine decoded and what it handed to the diarizer.
 */
internal class FakePcmDecoder : PcmDecoder {
    /** The source of every call, in order. */
    val decoded = mutableListOf<Path>()

    /** The PCM a stored canonical clip decodes to, in order; distinct from every chunk's samples. */
    val clipPcm = floatArrayOf(3.0f, 4.0f, 4.0f, 4.0f)

    override suspend fun decode(
        source: Path,
        target: Path,
    ): Long {
        decoded.add(source)
        val name = source.fileName.toString()
        val samples =
            if (name.startsWith("chunk-")) {
                val n = name.removePrefix("chunk-").removeSuffix(".flac").toInt()
                FloatArray(n + 1) { (n + 1).toFloat() }
            } else {
                clipPcm
            }
        val bytes =
            ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                samples.forEach { putFloat(it) }
            }
        withContext(Dispatchers.IO) { Files.write(target, bytes.array()) }
        return 0L
    }
}
