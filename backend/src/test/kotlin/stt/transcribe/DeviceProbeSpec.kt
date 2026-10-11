package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 32, cycle 2: the startup device probe — [DeviceProbe] runs whisper-cli once and parses the GPUs it
 * reports on stderr (`%s: found GPU device N: <description> (type: …, cnt: …)`, libwhisper; identical in 1.8.4 and 1.9.2). The stand-ins
 * exit 1, as whisper-cli does after failing to read the probe's `/dev/null` input. CPU-only output and a missing
 * executable yield no GPU. Real subprocesses, hence Integration (ADR 0021).
 */
class DeviceProbeSpec :
    BehaviorSpec({
        tags(Integration)

        val model = Path.of("/nonexistent-model.bin")

        given("a whisper-cli stand-in reporting two GPUs, then failing on the input") {
            `when`("the probe runs it") {
                then("it parses GPU 0 and GPU 1 with their descriptions, in order") {
                    val stderr =
                        listOf(
                            "load_backend: loaded Vulkan backend from /nix/store/x-whisper-cpp-1.9.2/lib/libggml-vulkan.so",
                            "whisper_backend_init_gpu: found GPU device 0: NVIDIA GeForce RTX 3060 (type: 2, cnt: 1)",
                            "whisper_backend_init_gpu: found GPU device 1: AMD Radeon RX 6600 (type: 1, cnt: 2)",
                            "error: failed to read audio file '/dev/null'",
                        )
                    val whisperCli = fakeWhisperCli(tempdir("stt-probe").toPath(), stderr, exitCode = 1)

                    val gpus = withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, whisperCli.toString()).gpus() }

                    gpus.shouldBe(listOf(GpuDevice(0, "NVIDIA GeForce RTX 3060"), GpuDevice(1, "AMD Radeon RX 6600")))
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

                    val gpus = withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, whisperCli.toString()).gpus() }

                    gpus.shouldBeEmpty()
                }
            }
        }

        given("whisper-cli is missing") {
            `when`("the probe runs it") {
                then("the probe tolerates the failure and reports no GPU, so the app still starts") {
                    val gpus =
                        withTimeout(10.seconds) { DeviceProbe(ProcessRunner(), model, "/nonexistent-whisper-cli").gpus() }

                    gpus.shouldBeEmpty()
                }
            }
        }
    })
