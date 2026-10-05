package stt.config

import io.kotest.assertions.throwables.shouldThrowUnit
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

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
                then("it fails instead of falling back to the default") {
                    shouldThrowUnit<NumberFormatException> {
                        Config.fromEnv(mapOf("STT_PORT" to "abc"))
                    }
                }
            }
        }
    })
