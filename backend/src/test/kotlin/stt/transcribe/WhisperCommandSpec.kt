package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.config.Device
import java.nio.file.Path

class WhisperCommandSpec :
    BehaviorSpec({
        given("a chunk to transcribe, with and without STT_THREADS and a previous chunk's tail") {
            `when`("the whisper-cli command is built") {
                then("it includes -t only when threads are configured and --prompt only when a previous tail exists") {
                    val model = Path.of("/models/ggml-tiny.en.bin")
                    val chunk = Path.of("/work/chunk-2.flac")
                    val prefix = Path.of("/work/chunk-2")

                    val configuredSettings = WhisperSettings(model, "en", threads = 4, device = Device.Cpu)
                    whisperCommand(configuredSettings, chunk, prefix, prompt = " ask what you can do")
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

                    val defaults = WhisperSettings(model, "en", threads = null, device = Device.Auto)
                    whisperCommand(defaults, chunk, prefix, prompt = null)
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
                }
            }
        }
    })
