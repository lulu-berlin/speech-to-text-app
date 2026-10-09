package stt.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

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
                FFMPEG +
                    listOf(
                        "-y",
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
        result.expectSuccess("decode $source")
        val sampleCount = withContext(Dispatchers.IO) { Files.size(pcm) } / BYTES_PER_SAMPLE
        if (sampleCount == 0L) throw AudioDecodeException("no audio decoded from $source")
        return sampleCount / SAMPLES_PER_MS
    }
}
