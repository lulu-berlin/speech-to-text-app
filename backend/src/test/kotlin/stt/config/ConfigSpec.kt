package stt.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class ConfigSpec :
    BehaviorSpec({

        given("an environment without STT_PORT") {
            `when`("loading configuration") {
                then("the port defaults to 8080") {
                    Config.fromEnv(TEST_REQUIRED_ENV).port.shouldBe(8080)
                }
            }
        }

        given("an environment with a non-numeric STT_PORT") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_PORT and the bad value") {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            Config.fromEnv(TEST_REQUIRED_ENV + ("STT_PORT" to "abc"))
                        }
                    error.message.shouldContain("STT_PORT")
                    error.message.shouldContain("abc")
                }
            }
        }

        given("an environment with an STT_PORT outside 1-65535") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_PORT and the value; endpoints 1 and 65535 load") {
                    for (bad in listOf("0", "70000")) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(TEST_REQUIRED_ENV + ("STT_PORT" to bad))
                            }
                        error.message.shouldContain("STT_PORT")
                        error.message.shouldContain(bad)
                    }
                    for (good in listOf("1", "65535")) {
                        Config.fromEnv(TEST_REQUIRED_ENV + ("STT_PORT" to good)).port.shouldBe(good.toInt())
                    }
                }
            }
        }

        given("an environment with STT_DATA_DIR unset or empty") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_DATA_DIR") {
                    for (env in listOf(TEST_REQUIRED_ENV - "STT_DATA_DIR", TEST_REQUIRED_ENV + ("STT_DATA_DIR" to ""))) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(env)
                            }
                        error.message.shouldContain("STT_DATA_DIR")
                    }
                }
            }
        }

        given("an environment with STT_SPEAKER_MODEL unset, empty, or set") {
            `when`("loading configuration") {
                then("unset or empty fails with an error naming STT_SPEAKER_MODEL; a set path is part of the configuration") {
                    for (env in listOf(TEST_REQUIRED_ENV - "STT_SPEAKER_MODEL", TEST_REQUIRED_ENV + ("STT_SPEAKER_MODEL" to ""))) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(env)
                            }
                        error.message.shouldContain("STT_SPEAKER_MODEL")
                    }
                    Config
                        .fromEnv(TEST_REQUIRED_ENV + ("STT_SPEAKER_MODEL" to "/models/campplus.onnx"))
                        .speakerModel
                        .shouldBe(Path.of("/models/campplus.onnx"))
                }
            }
        }

        given("an environment with STT_WHISPER_MODEL unset, empty, or set") {
            `when`("loading configuration") {
                then("unset or empty fails with an error naming STT_WHISPER_MODEL; a set path is part of the configuration") {
                    for (env in listOf(TEST_REQUIRED_ENV - "STT_WHISPER_MODEL", TEST_REQUIRED_ENV + ("STT_WHISPER_MODEL" to ""))) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(env)
                            }
                        error.message.shouldContain("STT_WHISPER_MODEL")
                    }
                    Config
                        .fromEnv(TEST_REQUIRED_ENV + ("STT_WHISPER_MODEL" to "/models/ggml-large-v3-turbo-q5_0.bin"))
                        .whisperModel
                        .shouldBe(Path.of("/models/ggml-large-v3-turbo-q5_0.bin"))
                }
            }
        }

        given("an environment with STT_HOST unset, empty, or set") {
            `when`("loading configuration") {
                then("unset or empty binds every interface (the historical default); a set host wins (desktop item 50)") {
                    Config.fromEnv(TEST_REQUIRED_ENV).host.shouldBe("0.0.0.0")
                    Config.fromEnv(TEST_REQUIRED_ENV + ("STT_HOST" to "")).host.shouldBe("0.0.0.0")
                    Config.fromEnv(TEST_REQUIRED_ENV + ("STT_HOST" to "127.0.0.1")).host.shouldBe("127.0.0.1")
                }
            }
        }

        given("STT_DEVICE and STT_THREADS set to values that used to stop startup") {
            `when`("configuration is loaded") {
                then("it loads exactly as without them: the device and thread count live only in the stored settings (item 32)") {
                    val withOldVariables = TEST_REQUIRED_ENV + ("STT_DEVICE" to "tpu") + ("STT_THREADS" to "four")

                    Config.fromEnv(withOldVariables).shouldBe(Config.fromEnv(TEST_REQUIRED_ENV))
                }
            }
        }
    })
