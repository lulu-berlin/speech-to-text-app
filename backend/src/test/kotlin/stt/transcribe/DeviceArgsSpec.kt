package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.config.Device

class DeviceArgsSpec :
    BehaviorSpec({
        given("the devices a setting can name") {
            `when`("the device is turned into whisper-cli flags") {
                then("auto adds no flag, cpu adds --no-gpu, GPU 0 adds --device 0, GPU 3 adds --device 3") {
                    // Oracle device_args: auto → [], cpu → --no-gpu, gpu → --device 0, N → --device N.
                    val expected =
                        mapOf(
                            Device.Auto to emptyList(),
                            Device.Cpu to listOf("--no-gpu"),
                            Device.Gpu(0) to listOf("--device", "0"),
                            Device.Gpu(3) to listOf("--device", "3"),
                        )
                    for ((device, flags) in expected) {
                        deviceArgs(device).shouldBe(flags)
                    }
                }
            }
        }
    })
