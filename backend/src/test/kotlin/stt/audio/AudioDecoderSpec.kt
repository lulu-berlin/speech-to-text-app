package stt.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
                    sineWav(source, seconds = 2.5, channels = 2)
                    val pcm = workDir.resolve("audio.f32")

                    val durationMs = decode(source, pcm)

                    // 2.5 s × 16 000 samples/s = 40 000 samples; × 4 bytes (float32, one channel) = 160 000 bytes.
                    Files.size(pcm).shouldBe(160_000L)
                    durationMs.shouldBe(2_500L) // 40 000 samples / 16 samples per ms
                    // The generated sine has amplitude 1/8: read as little-endian float32, the peak is ≈ 0.125.
                    peak(pcm).shouldBeBetween(0.12, 0.13, 0.0)
                }
            }
        }

        given("a file ffmpeg cannot read") {
            `when`("it is decoded") {
                then("decoding fails with ffmpeg's error message") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = Files.writeString(workDir.resolve("source.wav"), "not audio")

                    val error = shouldThrow<AudioDecodeException> { decode(source) }

                    error.message.shouldContain("ffmpeg failed to decode $source") // the oracle's wording
                    error.message.shouldContain("Invalid data found when processing input") // ffmpeg's stderr
                }
            }
        }

        given("a file with no audio: a 0 s WAV, which ffmpeg decodes successfully to zero samples") {
            `when`("it is decoded") {
                then("decoding fails with \"no audio decoded\"") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("source.wav")
                    emptyWav(source)

                    val error = shouldThrow<AudioDecodeException> { decode(source) }

                    error.message.shouldContain("no audio decoded from $source") // the oracle's wording
                }
            }
        }
    })

/** Decodes [source] with a fresh runner into [pcm] (beside the source by default), within a timeout. */
private suspend fun decode(
    source: Path,
    pcm: Path = source.resolveSibling("audio.f32"),
): Long = withTimeout(30.seconds) { AudioDecoder(ProcessRunner()).decode(source, pcm) }

private fun peak(pcm: Path): Double {
    val samples = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return (0 until samples.limit()).maxOf { abs(samples[it].toDouble()) }
}
