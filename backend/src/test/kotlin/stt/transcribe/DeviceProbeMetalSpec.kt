package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.process.ProcessResult
import java.nio.file.Path

class DeviceProbeMetalSpec :
    BehaviorSpec({
        given("the pinned 1.8.4 darwin whisper-cli, which enumerates Metal as MTL0") {
            `when`("the startup probe reads its stderr") {
                then("the probe reports gpu:0 named Metal (MTL0)") {
                    probeWith(METAL_STDERR).probe() shouldBe
                        DeviceProbeOutcome.Probed(listOf(GpuDevice(0, "Metal (MTL0)")))
                }
            }
        }

        given("the pinned darwin prebuild, which has no ggml GPU backend (CoreML only)") {
            `when`("the startup probe reads its stderr") {
                then("it reports no GPU, so Settings offers cpu and auto only, with no phantom GPU") {
                    probeWith(NO_GPU_STDERR).probe() shouldBe DeviceProbeOutcome.Probed(emptyList())
                }
            }
        }
    })

private fun probeWith(stderr: List<String>) =
    DeviceProbe(
        run = { ProcessResult(exitCode = 0, stdout = emptyList(), stderr = stderr) },
        model = Path.of("model.bin"),
        executable = "whisper-cli",
    )

/** The lines whisper.cpp 1.8.4's Metal build prints while enumerating its GPU (ggml device MTL0). */
private val METAL_STDERR =
    listOf(
        "ggml_metal_init: GPU name:   MTL0",
        "whisper_backend_init_gpu: device 0: MTL0 (type: 2)",
        "whisper_backend_init_gpu: found GPU device 0: MTL0 (type: 2, cnt: 0)",
        "whisper_backend_init_gpu: using MTL0 backend",
    )

/** The pinned darwin prebuild: a CoreML encoder and CPU only, so no ggml GPU device is registered. */
private val NO_GPU_STDERR =
    listOf(
        "whisper_backend_init_gpu: device 0: CPU (type: 1)",
        "whisper_backend_init_gpu: no GPU found",
        "whisper_init_state: CoreML model loaded",
    )
