package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerPlatformSpec :
    BehaviorSpec({
        given("darwin, where there is no setsid") {
            `when`("a command is run") {
                then("it is spawned directly, without setsid") {
                    var spawned: List<String>? = null
                    val runner =
                        ProcessRunner(
                            gracePeriod = 1.seconds,
                            startProcess = { builder ->
                                spawned = builder.command()
                                ExitedProcess(4242)
                            },
                            platform = ProcessPlatform.DARWIN,
                        )

                    runTest { runner.run(listOf("sleep", "0")) }

                    val command = spawned ?: error("the process was never spawned")
                    command.size shouldBe 2
                    command[0] shouldNotBe "setsid"
                    command[0].endsWith("/sleep") shouldBe true
                    command[1] shouldBe "0"
                }
            }

            `when`("its tree is paused, resumed and cancelled") {
                then("every signal targets the direct child, not a process group") {
                    val sent = mutableListOf<Pair<Int, Int>>()
                    var alive = true
                    val tree =
                        ProcessTree(4242, childAlive = { alive }, platform = ProcessPlatform.DARWIN) { pid, signal ->
                            sent += pid to signal
                            if (signal == 9) alive = false
                            0
                        }

                    tree.pause()
                    tree.resume()
                    runTest { tree.terminate(1.seconds) }

                    sent.map { it.second } shouldBe listOf(17, 19, 19, 15, 9)
                }
            }
        }

        given("Windows, where the host has no signals") {
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
private class ExitedProcess(
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
