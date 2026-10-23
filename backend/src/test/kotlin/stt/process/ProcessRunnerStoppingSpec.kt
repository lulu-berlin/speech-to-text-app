package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
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
                        val tool = File(bin, if (platform.windows) "whisper-cli.exe" else "whisper-cli")
                        tool.createNewFile()
                        tool.setExecutable(true)
                        val runner =
                            ProcessRunner(
                                gracePeriod = 1.seconds,
                                env = mapOf("PATH" to bin.absolutePath),
                                platform = platform,
                                stopping = { true },
                                startProcess = { FinishedTool(exitCode = 1) },
                            )

                        runTest {
                            shouldThrow<CancellationException> { runner.run(listOf("whisper-cli")) }
                        }
                    }
                }
            }
        }
    })

/** A process that has already exited with [exitCode]; its streams are empty. */
private class FinishedTool(
    private val exitCode: Int,
) : Process() {
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exitCode

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    override fun exitValue(): Int = exitCode

    override fun destroy() = Unit

    override fun pid(): Long = 4244

    override fun isAlive(): Boolean = false
}
