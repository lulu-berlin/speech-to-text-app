package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import stt.process.ProcessPlatform
import stt.process.ProcessResult
import java.nio.file.Path

/**
 * Roadmap item 49's discovered probe items: a whisper-cli that starts but cannot load its libraries prints no
 * device lines. On Windows the loader exits it with STATUS_DLL_NOT_FOUND (0xC0000135); on Linux ld.so prints
 * `error while loading shared libraries` (macOS dyld prints `Library not loaded`). That is a failed probe, not
 * the true "no GPU" outcome: the app must not start offering cpu/auto as if the probe had succeeded.
 */
class DeviceProbeLoadFailureSpec :
    BehaviorSpec({
        given("a whisper-cli that cannot load its DLLs and exits with STATUS_DLL_NOT_FOUND") {
            `when`("the startup probe runs it") {
                then("the load failure is reported instead of an empty device list") {
                    probeWith(
                        ProcessResult(exitCode = 0xC0000135.toInt(), stdout = emptyList(), stderr = emptyList()),
                        ProcessPlatform.WINDOWS,
                    ).probe()
                        .shouldBeInstanceOf<DeviceProbeOutcome.LaunchFailed>()
                }
            }
        }

        given("a whisper-cli whose shared library is missing on Linux, so ld.so fails before the backend runs") {
            `when`("the startup probe runs it") {
                then("the load failure is reported instead of an empty device list") {
                    val result =
                        ProcessResult(
                            exitCode = 127,
                            stdout = emptyList(),
                            stderr =
                                listOf(
                                    "whisper-cli: error while loading shared libraries: " +
                                        "libggml.so: cannot open shared object file: No such file or directory",
                                ),
                        )

                    probeWith(result, ProcessPlatform.LINUX)
                        .probe()
                        .shouldBeInstanceOf<DeviceProbeOutcome.LaunchFailed>()
                }
            }
        }
    })

/** The probe under test: the seam returns [result]; the platform only decides the input path, which the seam ignores. */
private fun probeWith(
    result: ProcessResult,
    platform: ProcessPlatform,
) = DeviceProbe(
    run = { result },
    model = Path.of("model.bin"),
    executable = "whisper-cli",
    platform = platform,
)
