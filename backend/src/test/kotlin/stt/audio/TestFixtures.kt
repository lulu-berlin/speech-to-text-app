package stt.audio

import kotlinx.coroutines.withTimeout
import stt.process.ProcessRunner
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
