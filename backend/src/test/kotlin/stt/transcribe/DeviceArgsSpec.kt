package stt.transcribe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.config.Config
import stt.config.TEST_REQUIRED_ENV

class DeviceArgsSpec :
    BehaviorSpec({
        given("STT_DEVICE values") {
            `when`("configuration is loaded and the device is turned into whisper-cli flags") {
                then("auto adds no flag, cpu adds --no-gpu, gpu adds --device 0, 3 adds --device 3; invalid values are rejected") {
                    // Oracle device_args: auto → [], cpu → --no-gpu, gpu → --device 0, N → --device N.
                    val expected =
                        mapOf(
                            "auto" to emptyList(),
                            "cpu" to listOf("--no-gpu"),
                            "gpu" to listOf("--device", "0"),
                            "3" to listOf("--device", "3"),
                        )
                    for ((raw, flags) in expected) {
                        deviceArgs(Config.fromEnv(env("STT_DEVICE" to raw)).device).shouldBe(flags)
                    }
                    // Unset: the oracle's default, auto.
                    deviceArgs(Config.fromEnv(env()).device).shouldBe(emptyList())

                    // Oracle parse_device rejects non-integers and negative indexes; empty is set-but-invalid.
                    for (bad in listOf("tpu", "-1", "1.5", "")) {
                        val error = shouldThrow<IllegalArgumentException> { Config.fromEnv(env("STT_DEVICE" to bad)) }
                        error.message.shouldContain("STT_DEVICE")
                        error.message.shouldContain("\"$bad\"")
                    }
                }
            }
        }
    })

private fun env(vararg vars: Pair<String, String>) = TEST_REQUIRED_ENV + vars
