package stt.http

import stt.audio.flacStreamInfo
import stt.audio.streamInfoTotalSamples
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** The exact sample count the served canonical FLAC's STREAMINFO declares. */
internal fun flacSamples(file: Path): Long = streamInfoTotalSamples(Files.readAllBytes(file))

/** The first audio packet's offset: the end of the FLAC metadata blocks. */
internal fun flacAudioStart(file: Path): Long = flacStreamInfo(file).audioStart.toLong()

/** Decodes [file] to raw f32le in [raw] and returns its sample count — what decoding really yields. */
internal suspend fun decodeSamples(
    runner: ProcessRunner,
    raw: Path,
    file: Path,
): Long {
    val result =
        runner.run(
            listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", file.toString(), "-f", "f32le", raw.toString()),
        )
    check(result.exitCode == 0) { "ffmpeg decode failed: ${result.stderr}" }
    return Files.size(raw) / 4
}
