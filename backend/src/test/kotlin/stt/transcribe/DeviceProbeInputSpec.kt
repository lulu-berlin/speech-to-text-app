package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.process.ProcessPlatform
import stt.process.ProcessResult
import java.nio.file.Path

/**
 * Roadmap item 49's discovered probe item: whisper-cli opens its `-f` input while parsing the command
 * line, before the backend enumerates GPUs. `/dev/null` does not exist on Windows, so passing it makes
 * whisper-cli exit with `input file not found` and the probe sees no device lines at all. The probe must
 * pass the platform's null device — `NUL` on Windows.
 */
class DeviceProbeInputSpec :
    BehaviorSpec({
        given("the Windows platform") {
            `when`("the startup probe runs whisper-cli") {
                then("it passes NUL as the input file") {
                    var command: List<String>? = null
                    val probe =
                        DeviceProbe(
                            run = { run ->
                                command = run
                                ProcessResult(exitCode = 0, stdout = emptyList(), stderr = emptyList())
                            },
                            model = Path.of("model.bin"),
                            executable = "whisper-cli",
                            platform = ProcessPlatform.WINDOWS,
                        )

                    probe.probe()

                    command shouldBe listOf("whisper-cli", "-m", "model.bin", "-f", "NUL")
                }
            }
        }
    })
