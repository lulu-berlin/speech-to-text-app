package stt.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/** The variables every configuration needs; each scenario adds or removes the one it is about. */
private val REQUIRED_ENV = mapOf("STT_DATA_DIR" to TEST_DATA_DIR, "STT_SPEAKER_MODEL" to TEST_SPEAKER_MODEL)

class ConfigSpec :
    BehaviorSpec({

        given("an environment without STT_PORT") {
            `when`("loading configuration") {
                then("the port defaults to 8080") {
                    Config.fromEnv(REQUIRED_ENV).port.shouldBe(8080)
                }
            }
        }

        given("an environment with a non-numeric STT_PORT") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_PORT and the bad value") {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            Config.fromEnv(REQUIRED_ENV + ("STT_PORT" to "abc"))
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
                                Config.fromEnv(REQUIRED_ENV + ("STT_PORT" to bad))
                            }
                        error.message.shouldContain("STT_PORT")
                        error.message.shouldContain(bad)
                    }
                    for (good in listOf("1", "65535")) {
                        Config.fromEnv(REQUIRED_ENV + ("STT_PORT" to good)).port.shouldBe(good.toInt())
                    }
                }
            }
        }

        given("an environment with STT_DATA_DIR unset or empty") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_DATA_DIR") {
                    for (env in listOf(REQUIRED_ENV - "STT_DATA_DIR", REQUIRED_ENV + ("STT_DATA_DIR" to ""))) {
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
                    for (env in listOf(REQUIRED_ENV - "STT_SPEAKER_MODEL", REQUIRED_ENV + ("STT_SPEAKER_MODEL" to ""))) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(env)
                            }
                        error.message.shouldContain("STT_SPEAKER_MODEL")
                    }
                    Config
                        .fromEnv(REQUIRED_ENV + ("STT_SPEAKER_MODEL" to "/models/campplus.onnx"))
                        .speakerModel
                        .shouldBe(Path.of("/models/campplus.onnx"))
                }
            }
        }
    })
