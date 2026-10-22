package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import stt.process.ProcessResult
import java.nio.file.Path

/**
 * Roadmap item 49's discovered probe item: a whisper-cli that starts but cannot load its libraries (on Windows
 * the loader exits it with STATUS_DLL_NOT_FOUND, 0xC0000135) prints no device lines. That is a failed probe, not
 * the true "no GPU" outcome: the app must not start offering cpu/auto as if the probe had succeeded.
 */
class DeviceProbeLoadFailureSpec :
    BehaviorSpec({
        given("a whisper-cli that cannot load its DLLs and exits with STATUS_DLL_NOT_FOUND") {
            `when`("the startup probe runs it") {
                then("the load failure is reported instead of an empty device list") {
                    val probe =
                        DeviceProbe(
                            run = {
                                ProcessResult(exitCode = -1073741515, stdout = emptyList(), stderr = emptyList())
                            },
                            model = Path.of("model.bin"),
                            executable = "whisper-cli",
                        )

                    probe.probe().shouldBeInstanceOf<DeviceProbeOutcome.LaunchFailed>()
                }
            }
        }
    })
