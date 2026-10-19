package stt.audio

import stt.process.ProcessRunner
import java.nio.file.Path

/**
 * Cuts one chunk's samples out of a piece of the canonical clip into the file whisper-cli reads (item 44). The
 * seam the engine drives; [FfmpegWindowCutter] is the ffmpeg implementation.
 */
interface WindowCutter {
    /**
     * Writes samples `[fromSample, toSample)` of [window] — a FLAC holding the clip's header and a run of its
     * one-second rows, counted from that run's first sample — to [target] as a 16 kHz mono 16-bit FLAC.
     */
    suspend fun cut(
        window: Path,
        fromSample: Long,
        toSample: Long,
        target: Path,
    )
}

/** Decodes the window, trims it by sample count and encodes the result, in one ffmpeg run. */
class FfmpegWindowCutter(
    private val runner: ProcessRunner,
) : WindowCutter {
    override suspend fun cut(
        window: Path,
        fromSample: Long,
        toSample: Long,
        target: Path,
    ) {
        val result =
            runner.run(
                FFMPEG +
                    listOf(
                        "-y",
                        "-loglevel",
                        "error",
                        "-i",
                        window.toString(),
                        "-vn",
                        "-af",
                        "atrim=start_sample=$fromSample:end_sample=$toSample",
                        "-ac",
                        "1",
                        "-sample_fmt",
                        "s16",
                        "-c:a",
                        "flac",
                        target.toString(),
                    ),
            )
        result.expectSuccess("cut samples $fromSample..$toSample of $window into $target")
    }
}
