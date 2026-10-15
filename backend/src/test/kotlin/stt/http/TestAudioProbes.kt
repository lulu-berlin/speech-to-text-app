package stt.http

import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** One ffprobe `-show_entries` query on [file]'s audio stream: one value per line. */
internal suspend fun ffprobe(
    runner: ProcessRunner,
    file: Path,
    entries: String,
): List<String> {
    val result =
        runner.run(
            listOf("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", entries, "-of", "csv=p=0", file.toString()),
        )
    check(result.exitCode == 0) { "ffprobe failed: ${result.stderr}" }
    return result.stdout.filter { it.isNotBlank() }
}

/** Decodes [file] to raw s16le in [raw] and returns its sample count — what decoding really yields. */
internal suspend fun decodeSamples(
    runner: ProcessRunner,
    raw: Path,
    file: Path,
): Long {
    val result =
        runner.run(
            listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", file.toString(), "-f", "s16le", raw.toString()),
        )
    check(result.exitCode == 0) { "ffmpeg decode failed: ${result.stderr}" }
    return Files.size(raw) / 2
}
