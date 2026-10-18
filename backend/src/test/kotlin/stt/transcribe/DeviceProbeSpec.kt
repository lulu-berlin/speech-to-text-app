package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap items 32 and 45: the startup device probe — [DeviceProbe] runs whisper-cli once and parses the physical
 * Vulkan GPUs its ggml backend enumerates on stderr (`ggml_vulkan: N = <name> (<driver>) | uma: …`, whisper.cpp
 * 1.8.4). The stand-ins exit 1, as whisper-cli does after failing to read the probe's `/dev/null` input. CPU-only
 * output is a successful zero-GPU probe; a missing executable is a reported launch failure (item 60). Real
 * subprocesses, hence Integration (ADR 0021).
 */
class DeviceProbeSpec :
    BehaviorSpec({
        tags(Integration)

        val model = Path.of("/nonexistent-model.bin")

        given("a whisper-cli stand-in whose Vulkan backend sees two physical GPUs behind its one ggml device") {
            `when`("the probe runs it") {
                then("it lists both physical GPUs with their names, in the order ggml printed them") {
                    // The user's machine, verbatim (item 45, 2026-10-13/14): libwhisper stops at the selected device,
                    // so with the probe's default `--device 0` it prints only Vulkan0 although ggml has Vulkan0 and Vulkan1.
                    val stderr =
                        listOf(
                            "ggml_vulkan: Found 2 Vulkan devices:",
                            "ggml_vulkan: 0 = AMD Ryzen 9 6900HS Creator Edition (RADV REMBRANDT) (radv) | uma: 1 | fp16: 1 | bf16: 0 | warp size: 32 | shared memory: 65536 | int dot: 1 | matrix cores: none",
                            "ggml_vulkan: 1 = NVIDIA GeForce RTX 3050 Laptop GPU (NVIDIA) | uma: 0 | fp16: 1 | bf16: 1 | warp size: 32 | shared memory: 49152 | int dot: 1 | matrix cores: NV_coopmat2",
                            "whisper_backend_init_gpu: device 0: Vulkan0 (type: 2)",
                            "whisper_backend_init_gpu: found GPU device 0: Vulkan0 (type: 2, cnt: 0)",
                            "error: failed to read audio file '/dev/null'",
                        )
                    val whisperCli = fakeWhisperCli(tempdir("stt-probe").toPath(), stderr, exitCode = 1)

                    val outcome = withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, whisperCli.toString()).probe() }

                    outcome shouldBe
                        DeviceProbeOutcome.Probed(
                            listOf(
                                GpuDevice(0, "AMD Ryzen 9 6900HS Creator Edition (RADV REMBRANDT)"),
                                GpuDevice(1, "NVIDIA GeForce RTX 3050 Laptop GPU"),
                            ),
                        )
                }
            }
        }

        given("a whisper-cli stand-in reporting only the CPU backend, as on a machine without a GPU") {
            `when`("the probe runs it") {
                then("no GPU is reported") {
                    // Recorded from whisper-cli 1.9.2 on a machine without a GPU (item 32 log).
                    val stderr =
                        listOf(
                            "whisper_backend_init_gpu: device 0: CPU (type: 0)",
                            "whisper_backend_init_gpu: no GPU found",
                            "error: failed to read audio file '/dev/null'",
                        )
                    val whisperCli = fakeWhisperCli(tempdir("stt-probe").toPath(), stderr, exitCode = 1)

                    val outcome = withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, whisperCli.toString()).probe() }

                    outcome shouldBe DeviceProbeOutcome.Probed(emptyList())
                }
            }
        }

        given("whisper-cli is missing") {
            `when`("the probe runs it") {
                then("the launch failure is reported with its message, not as an empty device list") {
                    val outcome = withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, "/nonexistent-whisper-cli").probe() }

                    val failed = outcome.shouldBeInstanceOf<DeviceProbeOutcome.LaunchFailed>()
                    failed.message shouldContain "/nonexistent-whisper-cli"
                }
            }
        }
    })
