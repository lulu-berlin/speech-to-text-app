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
                    Config.fromEnv(mapOf()).port.shouldBe(8080)
                }
            }
        }

        given("an environment with a non-numeric STT_PORT") {
            `when`("loading configuration") {
                then("it fails with an error naming STT_PORT and the bad value") {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            Config.fromEnv(mapOf("STT_PORT" to "abc"))
                        }
                    error.message.shouldContain("STT_PORT")
                    error.message.shouldContain("abc")
                }
            }
        }
    })
