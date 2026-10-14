package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Roadmap items 32 and 45: the parse of whisper-cli's ggml-vulkan device lines, without a subprocess (the probe's
 * Integration spec covers the run itself). [stt.Main] calls the probe before the server starts, so a line the
 * parser cannot make a device of must be ignored like any other unrecognized line — the app starts with no GPU
 * offered, rather than failing before it serves.
 */
class VulkanDeviceLinesSpec :
    BehaviorSpec({
        given("a device line whose index does not fit an Int, between two well-formed ones") {
            `when`("the probe parses the output") {
                then("the line is ignored and the devices it can read still parse, in order") {
                    // A garbled or future-format device line must not abort the parse: the index is an unbounded
                    // digit run in ggml's format, and toInt() would throw on one that overflows.
                    val stderr =
                        listOf(
                            "ggml_vulkan: Found 3 Vulkan devices:",
                            "ggml_vulkan: 0 = AMD Ryzen 9 6900HS Creator Edition (RADV REMBRANDT) (radv) | uma: 1 | fp16: 1",
                            "ggml_vulkan: 99999999999 = Bogus Device (bogus) | uma: 0 | fp16: 0",
                            "ggml_vulkan: 1 = NVIDIA GeForce RTX 3050 Laptop GPU (NVIDIA) | uma: 0 | fp16: 1",
                        )

                    parseVulkanDeviceLines(stderr) shouldBe
                        listOf(
                            GpuDevice(0, "AMD Ryzen 9 6900HS Creator Edition (RADV REMBRANDT)"),
                            GpuDevice(1, "NVIDIA GeForce RTX 3050 Laptop GPU"),
                        )
                }
            }
        }
    })
