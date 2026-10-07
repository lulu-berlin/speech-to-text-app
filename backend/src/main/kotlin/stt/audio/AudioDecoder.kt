package stt.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** Decoded PCM: little-endian float32, one channel, 16 kHz (ported from the oracle's `decode_pcm`). */
private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 4
private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000

class AudioDecoder(
    private val runner: ProcessRunner,
) {
    /** Decodes [source] into the PCM file [pcm] and returns the decoded duration in milliseconds (sample count / 16). */
    suspend fun decode(
        source: Path,
        pcm: Path,
    ): Long {
        val result =
            runner.run(
                listOf(
                    "ffmpeg",
                    "-hide_banner",
                    "-nostats",
                    "-loglevel",
                    "error",
                    "-i",
                    source.toString(),
                    "-f",
                    "f32le",
                    "-ac",
                    "1",
                    "-ar",
                    SAMPLE_RATE.toString(),
                    pcm.toString(),
                ),
            )
        if (result.exitCode != 0) {
            throw AudioDecodeException("ffmpeg failed to decode $source: ${result.stderr.joinToString("\n").trim()}")
        }
        val sampleCount = withContext(Dispatchers.IO) { Files.size(pcm) } / BYTES_PER_SAMPLE
        return sampleCount / SAMPLES_PER_MS
    }
}
