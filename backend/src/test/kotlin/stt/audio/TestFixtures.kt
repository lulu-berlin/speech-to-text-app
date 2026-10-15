package stt.audio

import kotlinx.coroutines.withTimeout
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Generates a fixture file at [target] from the lavfi source [lavfi] with ffmpeg, plus [outputOptions]. */
internal suspend fun generate(
    target: Path,
    lavfi: String,
    vararg outputOptions: String,
) {
    val command =
        FFMPEG + listOf("-loglevel", "error", "-f", "lavfi", "-i", lavfi) +
            outputOptions + target.toString()
    val result = withTimeout(30.seconds) { ProcessRunner().run(command) }
    result.expectSuccess("generate fixture $target")
}

/** A 16 kHz mono sine-wave FLAC `<name>.flac` in [dir], shaped like a stored chunk, for the real join. */
internal suspend fun sineFlac(
    dir: Path,
    name: String,
    frequency: String,
    seconds: String,
): Path =
    dir.resolve("$name.flac").also { file ->
        generate(file, "sine=frequency=$frequency:duration=$seconds", "-ar", "16000", "-ac", "1", "-c:a", "flac")
    }

/**
 * Writes [samples] of 16 kHz mono s16 pink noise to [target], zeroed inside each `(from, to)` second range of
 * [silences]. No timeout of its own: it also runs inside `runTest`, whose virtual clock would fire one at once.
 */
internal suspend fun generateNoise(
    target: Path,
    samples: Int,
    silences: List<Pair<Double, Double>>,
) {
    val mute = silences.joinToString(",") { (from, to) -> "volume=enable='between(t,$from,$to)':volume=0" }
    val filters = listOfNotNull(mute.ifEmpty { null }, "atrim=end_sample=$samples").joinToString(",")
    val noise = "anoisesrc=sample_rate=16000:color=pink:amplitude=0.5:seed=1:duration=${samples / 16_000 + 1}"
    val command =
        FFMPEG + listOf("-y", "-loglevel", "error", "-f", "lavfi", "-i", noise) +
            listOf("-af", filters, "-ac", "1", "-c:a", "pcm_s16le", target.toString())
    ProcessRunner().run(command).expectSuccess("generate $target")
}

/**
 * The samples of [input] as raw little-endian 16-bit mono, decoded by ffmpeg into a scratch file under [scratchDir]
 * that is removed again; [inputOptions] describe a raw input. No timeout of its own, as for [generateNoise].
 */
internal suspend fun toS16(
    input: Path,
    scratchDir: Path,
    vararg inputOptions: String,
): ByteArray {
    Files.createDirectories(scratchDir)
    val raw = scratchDir.resolve("${input.fileName}.${System.nanoTime()}.s16")
    val command =
        FFMPEG + listOf("-loglevel", "error", "-y") + inputOptions +
            listOf("-i", input.toString(), "-f", "s16le", "-ac", "1", raw.toString())
    ProcessRunner().run(command).expectSuccess("convert $input to raw s16le")
    return Files.readAllBytes(raw).also { Files.delete(raw) }
}
