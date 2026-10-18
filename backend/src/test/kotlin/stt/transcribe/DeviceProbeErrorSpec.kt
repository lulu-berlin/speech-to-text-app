package stt.transcribe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Item 60: only a launch failure (`IOException`) becomes a reported probe failure. Any other throwable is a bug and
 * propagates, so startup fails loudly instead of the app running on a false "no GPU"; this pins that the narrowing
 * does not grow back into a catch-all.
 */
class DeviceProbeErrorSpec :
    BehaviorSpec({
        given("a device probe whose runner throws an Error") {
            `when`("the probe runs") {
                then("the Error propagates instead of becoming a reported probe failure") {
                    val error = NoClassDefFoundError("Could not initialize class ai.onnxruntime.OrtEnvironment")
                    val probe = DeviceProbe({ throw error }, Path.of("/m.bin"), "whisper-cli")

                    shouldThrow<NoClassDefFoundError> { probe.probe() } shouldBe error
                }
            }
        }
    })
