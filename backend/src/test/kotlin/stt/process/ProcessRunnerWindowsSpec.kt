package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.File
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
