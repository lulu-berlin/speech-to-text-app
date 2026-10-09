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

        given("STT_DEVICE values") {
            `when`("configuration is loaded") {
                then("auto, cpu, gpu and 3 load as Auto, Cpu, Gpu(0), Gpu(3); unset defaults to Auto; invalid values are rejected") {
                    // Oracle parse_device: auto, cpu, gpu (= GPU 0), or a GPU index N.
                    val expected =
                        mapOf(
                            "auto" to Device.Auto,
                            "cpu" to Device.Cpu,
                            "gpu" to Device.Gpu(0),
                            "3" to Device.Gpu(3),
                        )
                    for ((raw, device) in expected) {
                        Config.fromEnv(TEST_REQUIRED_ENV + ("STT_DEVICE" to raw)).device.shouldBe(device)
                    }
                    // Unset: the oracle's default, auto.
                    Config.fromEnv(TEST_REQUIRED_ENV).device.shouldBe(Device.Auto)

                    // Oracle parse_device rejects non-integers and negative indexes; empty is set-but-invalid.
                    for (bad in listOf("tpu", "-1", "1.5", "")) {
                        val error = shouldThrow<IllegalArgumentException> { Config.fromEnv(TEST_REQUIRED_ENV + ("STT_DEVICE" to bad)) }
                        error.message.shouldContain("STT_DEVICE")
                        error.message.shouldContain("\"$bad\"")
                    }
                }
            }
        }

        given("STT_THREADS values") {
            `when`("configuration is loaded") {
                then("4 loads as 4 and unset leaves threads unset; invalid values are rejected") {
                    Config.fromEnv(TEST_REQUIRED_ENV + ("STT_THREADS" to "4")).threads.shouldBe(4)
                    Config.fromEnv(TEST_REQUIRED_ENV).threads.shouldBe(null)

                    // A thread count must be an integer >= 1; empty is set-but-invalid.
                    for (bad in listOf("", "0", "-2", "four", "1.5")) {
                        val error = shouldThrow<IllegalArgumentException> { Config.fromEnv(TEST_REQUIRED_ENV + ("STT_THREADS" to bad)) }
                        error.message.shouldContain("STT_THREADS")
                        error.message.shouldContain("\"$bad\"")
                    }
                }
            }
        }
    })
