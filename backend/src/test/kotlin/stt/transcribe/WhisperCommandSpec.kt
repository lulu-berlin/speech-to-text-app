package stt.transcribe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.config.Config
import stt.config.TEST_DATA_DIR
import java.nio.file.Path

class WhisperCommandSpec :
    BehaviorSpec({
        given("a chunk to transcribe, with and without STT_THREADS and a previous chunk's tail") {
            `when`("the whisper-cli command is built") {
                then("it includes -t only when threads are configured and --prompt only when a previous tail exists") {
                    val model = Path.of("/models/ggml-tiny.en.bin")
                    val chunk = Path.of("/work/chunk-2.flac")
                    val prefix = Path.of("/work/chunk-2")

                    val configured = Config.fromEnv(env("STT_THREADS" to "4", "STT_DEVICE" to "cpu"))
                    whisperCommand(model, chunk, "en", configured.threads, configured.device, prefix, prompt = " ask what you can do")
                        .shouldBe(
                            listOf(
                                "whisper-cli",
                                "-m",
                                "/models/ggml-tiny.en.bin",
                                "-f",
                                "/work/chunk-2.flac",
                                "-l",
                                "en",
                                "-t",
                                "4",
                                "--no-gpu",
                                "-oj",
                                "-of",
                                "/work/chunk-2",
                                "-pp",
                                "--prompt",
                                " ask what you can do",
                            ),
                        )

                    val defaults = Config.fromEnv(env())
                    whisperCommand(model, chunk, "en", defaults.threads, defaults.device, prefix, prompt = null)
                        .shouldBe(
                            listOf(
                                "whisper-cli",
                                "-m",
                                "/models/ggml-tiny.en.bin",
                                "-f",
                                "/work/chunk-2.flac",
                                "-l",
                                "en",
                                "-oj",
                                "-of",
                                "/work/chunk-2",
                                "-pp",
                            ),
                        )

                    // A thread count must be an integer >= 1; empty is set-but-invalid.
                    for (bad in listOf("", "0", "-2", "four", "1.5")) {
                        val error = shouldThrow<IllegalArgumentException> { Config.fromEnv(env("STT_THREADS" to bad)) }
                        error.message.shouldContain("STT_THREADS")
                        error.message.shouldContain("\"$bad\"")
                    }
                }
            }
        }
    })

private fun env(vararg vars: Pair<String, String>) = mapOf("STT_DATA_DIR" to TEST_DATA_DIR, *vars)
