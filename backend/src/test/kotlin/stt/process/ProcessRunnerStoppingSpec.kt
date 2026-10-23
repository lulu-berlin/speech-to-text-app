package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 49's review finding: the decided known-stopped-by-us flag. A tool that dies while the backend is
 * stopping was stopped by the shutdown, not by a fault of its own, so the run is a cancellation (the engine then
 * leaves the task for recovery) and never a failed result. Linux shares Darwin's path here, except for the process
 * group it signals; Darwin is used so the test never signals a real group.
 */
class ProcessRunnerStoppingSpec :
    BehaviorSpec({
        for ((name, platform) in listOf("Darwin" to ProcessPlatform.DARWIN, "Windows" to ProcessPlatform.WINDOWS)) {
            given("$name, with the backend stopping") {
                `when`("a tool exits with a non-zero code") {
                    then("the run is a cancellation, not a failed result") {
                        val bin = tempdir("stt-stopping")
                        // `.exe` because the host may be Windows, where Files.isExecutable needs the extension
                        // even though the simulated platform may be Darwin (which appends no suffix).
                        val tool = File(bin, "whisper-cli.exe")
                        tool.createNewFile()
                        tool.setExecutable(true)
                        val runner =
                            ProcessRunner(
                                gracePeriod = 1.seconds,
                                env = mapOf("PATH" to bin.absolutePath),
                                platform = platform,
                                stopping = { true },
                                startProcess = { ExitedProcess(exitCode = 1) },
                            )

                        runTest {
                            shouldThrow<CancellationException> { runner.run(listOf("whisper-cli.exe")) }
                        }
                    }
                }
            }
        }
    })
