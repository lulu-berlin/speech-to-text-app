package stt.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import stt.audio.AudioDecodeException
import stt.audio.BYTES_PER_SAMPLE
import stt.audio.PcmDecoder
import stt.audio.SAMPLE_RATE
import stt.db.CanonicalClipRepository
import stt.db.ScratchDir
import stt.diarization.PcmSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The diarization's [PcmSource] over a task's stored canonical clip (items 43/44): a requested range of
 * whole-clip samples becomes the clip's one-second rows that hold it — the stored header followed by those rows,
 * exactly what `copySecondsTo` writes and what whisper's chunk inputs are cut from — decoded with the app's
 * decoder into the task's scratch directory and read back as that range's own floats.
 *
 * So a window is the only clip audio in heap while it is embedded (at most the capped 20 s window plus the whole
 * second it rounds to), however long the clip is; both scratch files are removed on every path. [totalSamples] is
 * the clip's exact sample count, which the caller reads from the stored header's STREAMINFO, so the windowing of
 * a segment near or past the clip's end matches the whole-clip PCM's.
 */
internal class ClipPcmSource(
    private val taskId: Long,
    override val totalSamples: Int,
    private val clipAudio: CanonicalClipRepository,
    private val pcmDecoder: PcmDecoder,
    private val scratchDir: ScratchDir,
) : PcmSource {
    override suspend fun samples(
        fromSample: Int,
        toSample: Int,
    ): FloatArray {
        require(fromSample >= 0 && toSample >= fromSample && toSample <= totalSamples) {
            "range [$fromSample, $toSample) of a clip of $totalSamples samples"
        }
        if (toSample == fromSample) return FloatArray(0)
        // The whole seconds the range touches: the first one holds fromSample, the last one toSample - 1.
        val firstSecond = fromSample / SAMPLE_RATE
        val lastSecond = (toSample + SAMPLE_RATE - 1) / SAMPLE_RATE
        val window = scratchDir.diarizeWindow(taskId)
        val pcm = scratchDir.diarizePcm(taskId)
        try {
            withContext(Dispatchers.IO) {
                Files.newOutputStream(window).use { out -> clipAudio.copySecondsTo(taskId, firstSecond, lastSecond, out) }
            }
            pcmDecoder.decode(window, pcm)
            val offset = (fromSample - firstSecond * SAMPLE_RATE).toLong() * BYTES_PER_SAMPLE
            val count = toSample - fromSample
            return withContext(Dispatchers.IO) { readFloats(pcm, offset, count) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(window)
                Files.deleteIfExists(pcm)
            }
        }
    }

    /** Reads [count] little-endian float32 samples of [pcm] from byte [offset]; audio that ends sooner is refused. */
    private fun readFloats(
        pcm: Path,
        offset: Long,
        count: Int,
    ): FloatArray {
        val bytes = ByteBuffer.allocate(count * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(pcm, StandardOpenOption.READ).use { channel ->
            var position = offset
            while (bytes.hasRemaining()) {
                val read = channel.read(bytes, position)
                if (read < 0) throw AudioDecodeException("$pcm holds fewer than $count samples from byte $offset")
                position += read
            }
        }
        bytes.flip()
        return FloatArray(count) { bytes.getFloat() }
    }
}
