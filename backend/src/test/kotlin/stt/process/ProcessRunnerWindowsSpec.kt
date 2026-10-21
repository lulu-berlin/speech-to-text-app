package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerWindowsSpec :
    BehaviorSpec({
        given("Windows, where there is no setsid and no executable bit") {
            `when`("a bare tool name is run") {
                then("it is spawned directly, resolved to its .exe through the ';' PATH, with NUL as stdin") {
                    val bin = tempdir("stt-win-path")
                    val whisper = File(bin, "whisper-cli.exe").apply { createNewFile() }
                    var builder: ProcessBuilder? = null
                    val runner =
                        ProcessRunner(
                            gracePeriod = 1.seconds,
                            platform = ProcessPlatform.WINDOWS,
                            env = mapOf("PATH" to bin.absolutePath),
                            startProcess = { spawned ->
                                builder = spawned
                                WindowsExitedProcess(4242)
                            },
                        )

                    runTest { runner.run(listOf("whisper-cli", "-m", "model.bin")) }

                    val spawned = builder ?: error("the process was never spawned")
                    spawned.command() shouldBe listOf(whisper.absolutePath, "-m", "model.bin")
                    spawned.command().first() shouldNotBe "setsid"
                    spawned.redirectInput().file() shouldBe File("NUL")
                }
            }

            `when`("the platform is asked for its process-control facts") {
                then("it owns no process group and sends no signals") {
                    val platform = ProcessPlatform.forOsName("Windows 11")

                    platform.processGroups shouldBe false
                    platform.signals shouldBe false
                }
            }
        }
    })

/** A process that has already exited: its streams are empty and its exit code is 0. */
private class WindowsExitedProcess(
    private val pid: Long,
) : Process() {
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun pid(): Long = pid

    override fun isAlive(): Boolean = false
}
