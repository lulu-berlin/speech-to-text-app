package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

class AudioDecoderSpec :
    BehaviorSpec({
        tags(Integration)

        given("a 2.5 s, 440 Hz sine WAV at 44.1 kHz stereo") {
            `when`("it is decoded") {
                then("the PCM file holds mono 16 kHz float32 samples and the duration is the sample count / 16 ms") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("source.wav")
                    generate(source, "sine=frequency=440:sample_rate=44100:duration=2.5", "-ac", "2")
                    val pcm = workDir.resolve("audio.f32")

                    val durationMs = withTimeout(30.seconds) { AudioDecoder(ProcessRunner()).decode(source, pcm) }

                    // 2.5 s × 16 000 samples/s = 40 000 samples; × 4 bytes (float32, one channel) = 160 000 bytes.
                    Files.size(pcm).shouldBe(160_000L)
                    durationMs.shouldBe(2_500L) // 40 000 samples / 16 samples per ms
                    // lavfi sine has amplitude 1/8: read as little-endian float32, the peak is ≈ 0.125.
                    peak(pcm).shouldBeBetween(0.12, 0.13, 0.0)
                }
            }
        }
    })

/** Generates a fixture file at [target] from the lavfi source [lavfi] with ffmpeg, plus [outputOptions]. */
private suspend fun generate(
    target: Path,
    lavfi: String,
    vararg outputOptions: String,
) {
    val command =
        listOf("ffmpeg", "-hide_banner", "-nostats", "-loglevel", "error", "-f", "lavfi", "-i", lavfi) +
            outputOptions + target.toString()
    val result = withTimeout(30.seconds) { ProcessRunner().run(command) }
    check(result.exitCode == 0) { "fixture generation failed: ${result.stderr}" }
}

private fun peak(pcm: Path): Double {
    val samples = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return (0 until samples.limit()).maxOf { abs(samples[it].toDouble()) }
}
