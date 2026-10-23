package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
                        val runner =
                            ProcessRunner(
                                gracePeriod = 1.seconds,
                                platform = platform,
                                stopping = { true },
                                startProcess = { ExitedProcess(exitCode = 1) },
                            )

                        // Resolution runs before the spawn seam, so the command must exist on the host: the JVM's
                        // own binary does everywhere (an absolute path is used as given, no PATH lookup).
                        runTest {
                            shouldThrow<CancellationException> { runner.run(listOf(hostExecutable())) }
                        }
                    }
                }
            }
        }
    })

/** A command that resolves on every host: the running JVM's own executable (resolution runs before the seam). */
private fun hostExecutable(): String =
    ProcessHandle
        .current()
        .info()
        .command()
        .orElseThrow()
