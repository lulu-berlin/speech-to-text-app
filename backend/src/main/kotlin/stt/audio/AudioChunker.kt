package stt.audio

import stt.process.ProcessRunner
import java.nio.file.Path

private const val TARGET_MS = 5 * 60 * 1000L
private const val CAP_MS = 6 * 60 * 1000L
private const val SILENCE_NOISE = "-30dB"
private const val SILENCE_MIN_SECONDS = "0.5"

/** Decodes a source, plans its chunks at silences, and exports each chunk as FLAC (ADR 0004, ADR 0024). */
class AudioChunker(
    private val runner: ProcessRunner,
) {
    private val decoder = AudioDecoder(runner)

    /** Chunks [source] into FLAC files in [workDir]; the decoded PCM is written there too. */
    suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio {
        val pcm = workDir.resolve("audio.f32")
        val durationMs = decoder.decode(source, pcm)
        val silences = detectSilences(source, durationMs)
        val chunks =
            planChunks(durationMs, silences, TARGET_MS, CAP_MS).mapIndexed { idx, planned ->
                val file = workDir.resolve("chunk-$idx.flac")
                export(pcm, planned, file)
                ExportedChunk(planned, file)
            }
        return ChunkedAudio(durationMs, chunks)
    }

    private suspend fun detectSilences(
        source: Path,
        durationMs: Long,
    ): List<Silence> {
        val result =
            runner.run(
                listOf(
                    "ffmpeg",
                    "-hide_banner",
                    "-nostats",
                    "-i",
                    source.toString(),
                    "-af",
                    "silencedetect=noise=$SILENCE_NOISE:d=$SILENCE_MIN_SECONDS",
                    "-f",
                    "null",
                    "-",
                ),
            )
        return parseSilences(result.stderr, durationMs)
    }

    /** Cuts [chunk] sample-exactly from the decoded [pcm] into a 16-bit FLAC [file]. */
    private suspend fun export(
        pcm: Path,
        chunk: PlannedChunk,
        file: Path,
    ) {
        runner.run(
            listOf(
                "ffmpeg",
                "-hide_banner",
                "-nostats",
                "-loglevel",
                "error",
                "-f",
                "f32le",
                "-ar",
                SAMPLE_RATE.toString(),
                "-ac",
                "1",
                "-i",
                pcm.toString(),
                "-af",
                "atrim=start_sample=${chunk.startMs * SAMPLES_PER_MS}:end_sample=${chunk.endMs * SAMPLES_PER_MS}",
                "-c:a",
                "flac",
                "-sample_fmt",
                "s16",
                file.toString(),
            ),
        )
    }
}
