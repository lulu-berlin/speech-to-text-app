package stt.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ConfigSpec :
    BehaviorSpec({

        given("an environment without STT_PORT") {
            `when`("loading configuration") {
                then("the port defaults to 8080") {
                    Config.fromEnv(mapOf("STT_DATA_DIR" to DATA_DIR)).port.shouldBe(8080)
                }
            }
        }

        given("an environment with a non-numeric STT_PORT") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_PORT and the bad value") {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            Config.fromEnv(mapOf("STT_DATA_DIR" to DATA_DIR, "STT_PORT" to "abc"))
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
                                Config.fromEnv(mapOf("STT_DATA_DIR" to DATA_DIR, "STT_PORT" to bad))
                            }
                        error.message.shouldContain("STT_PORT")
                        error.message.shouldContain(bad)
                    }
                    for (good in listOf("1", "65535")) {
                        Config.fromEnv(mapOf("STT_DATA_DIR" to DATA_DIR, "STT_PORT" to good)).port.shouldBe(good.toInt())
                    }
                }
            }
        }

        given("an environment with STT_DATA_DIR unset or empty") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_DATA_DIR") {
                    for (env in listOf(mapOf(), mapOf("STT_DATA_DIR" to ""))) {
                        val error =
                            shouldThrow<IllegalArgumentException> {
                                Config.fromEnv(env)
                            }
                        error.message.shouldContain("STT_DATA_DIR")
                    }
                }
            }
        }
    })

private const val DATA_DIR = "/var/lib/stt"
