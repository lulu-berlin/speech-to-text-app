package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.config.Device
import stt.process.ProcessRunner
import stt.requireTestEnv
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class JfkIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the fixture model and speech clip from the environment") {
            `when`("jfk.wav is transcribed on CPU") {
                then("it yields non-empty text with strictly increasing timestamps") {
                    val (model, jfk) = transcriptFixtures()
                    val settings = WhisperSettings(model, "en", threads = null, Device.Cpu)
                    val outputPrefix = tempdir("stt-jfk").toPath().resolve("jfk")

                    // 11 s clip: one chunk, well under whisper-cli's default 30 s window.
                    val segments =
                        withTimeout(120.seconds) {
                            ChunkTranscriber(ProcessRunner())
                                .transcribe(
                                    settings = settings,
                                    audio = jfk,
                                    outputPrefix = outputPrefix,
                                    prompt = null,
                                    onProgress = {},
                                )
                        }

                    with(segments) {
                        shouldNotBeEmpty()
                        // whisper-cli segments always carry text; trimming happens when rendering (item 10's spec).
                        map { it.text }
                            .joinToString("")
                            .trim()
                            .length
                            .shouldBeGreaterThan(0)
                        // Offsets are milliseconds from the audio's start (item 07's spec), so the
                        // first segment begins at the clip's start.
                        first().startMs.shouldBe(0L)
                    }
                    // Segments are stitched contiguously from the start of the clip, so their starts
                    // strictly increase.
                    segments.map { it.startMs }.zipWithNext().forEach { (a, b) -> a.shouldBeLessThan(b) }
                }
            }
        }
    })

/**
 * The item 07 integration fixtures, read from the environment in exactly one place: the ggml-tiny.en model and
 * whisper.cpp's samples/jfk.wav, exported by `devenv.nix` (`nix/models.nix`, `nix/test-fixtures.nix`).
 */
internal fun transcriptFixtures(): Pair<Path, Path> = requireTestEnv("TEST_WHISPER_TINY_EN_MODEL") to requireTestEnv("TEST_JFK_WAV")
