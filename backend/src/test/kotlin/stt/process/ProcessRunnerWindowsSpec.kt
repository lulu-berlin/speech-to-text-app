package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerWindowsSpec :
    BehaviorSpec({
        given("Windows, where there is no setsid and no executable bit") {
            `when`("a bare tool name is run") {
                then("it is spawned directly, resolved to its .exe through the ';' PATH, with NUL as stdin") {
                    val bin = tempdir("stt-win-path")
                    val whisper = File(bin, "whisper-cli.exe").apply { createNewFile() }

                    val spawned =
                        spawnedOnWindows(mapOf("PATH" to bin.absolutePath), listOf("whisper-cli", "-m", "model.bin"))

                    spawned.command() shouldBe listOf(whisper.absolutePath, "-m", "model.bin")
                    spawned.command().first() shouldNotBe "setsid"
                    spawned.redirectInput().file() shouldBe File("NUL")
                }
            }

            `when`("the environment spells the variable `Path`, as Windows itself does") {
                then("the bare tool name still resolves through it") {
                    val bin = tempdir("stt-win-path-case")
                    val ffmpeg = File(bin, "ffmpeg.exe").apply { createNewFile() }

                    val spawned = spawnedOnWindows(mapOf("Path" to bin.absolutePath), listOf("ffmpeg", "-version"))

                    spawned.command() shouldBe listOf(ffmpeg.absolutePath, "-version")
                }
            }

            `when`("its coroutine is cancelled while the tool is starting") {
                then("the direct child is destroyed and waited for, bounded, with no signal") {
                    val bin = tempdir("stt-win-kill")
                    File(bin, "whisper-cli.exe").createNewFile()
                    val child = RecordingProcess()
                    lateinit var job: Job
                    val runner =
                        ProcessRunner(
                            gracePeriod = 300.milliseconds,
                            platform = ProcessPlatform.WINDOWS,
                            env = mapOf("Path" to bin.absolutePath),
                            startProcess = { child.also { job.cancel() } },
                        )

                    runTest {
                        job =
                            launch(start = CoroutineStart.LAZY) {
                                shouldThrow<CancellationException> { runner.run(listOf("whisper-cli")) }
                            }
                        job.start()
                        job.join()
                    }

                    child.destroyed shouldBe true
                    child.waitedForMs shouldBe 300L
                }
            }

            `when`("a console Ctrl-C ends the tool before the backend knows it is stopping") {
                then("its STATUS_CONTROL_C_EXIT is a cancellation, not a failed result") {
                    val bin = tempdir("stt-win-ctrl-c")
                    File(bin, "whisper-cli.exe").createNewFile()
                    val runner =
                        ProcessRunner(
                            gracePeriod = 1.seconds,
                            platform = ProcessPlatform.WINDOWS,
                            env = mapOf("Path" to bin.absolutePath),
                            startProcess = { ExitedProcess(exitCode = 0xC000013A.toInt()) },
                        )

                    runTest {
                        shouldThrow<CancellationException> { runner.run(listOf("whisper-cli")) }
                    }
                }
            }
        }
    })

/** The [ProcessBuilder] a Windows-platform runner spawns for [command] with [env] as its environment. */
private fun spawnedOnWindows(
    env: Map<String, String>,
    command: List<String>,
): ProcessBuilder {
    var builder: ProcessBuilder? = null
    val runner =
        ProcessRunner(
            gracePeriod = 1.seconds,
            platform = ProcessPlatform.WINDOWS,
            env = env,
            startProcess = { spawned ->
                builder = spawned
                ExitedProcess()
            },
        )
    runTest { runner.run(command) }
    return builder ?: error("the process was never spawned")
}

/** A process that never runs: it records how it is ended instead. */
private class RecordingProcess : Process() {
    var destroyed = false
    var waitedForMs: Long? = null

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        waitedForMs = unit.toMillis(timeout)
        return true
    }

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
        destroyed = true
        return this
    }

    override fun pid(): Long = 4246

    override fun isAlive(): Boolean = false
}
