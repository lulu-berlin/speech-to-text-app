package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerPlatformSpec :
    BehaviorSpec({
        given("darwin, where there is no setsid") {
            `when`("a command is run") {
                then("it is spawned directly, without setsid").config(enabled = !ProcessPlatform.current().windows) {
                    var spawned: List<String>? = null
                    val runner =
                        ProcessRunner(
                            gracePeriod = 1.seconds,
                            startProcess = { builder ->
                                spawned = builder.command()
                                ExitedProcess()
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

            `when`("a terminal Ctrl-C reaches the tool, which shares the terminal's process group") {
                then("its SIGINT exit (130) is a cancellation, not a failed result") {
                    val runner =
                        ProcessRunner(
                            gracePeriod = 1.seconds,
                            platform = ProcessPlatform.DARWIN,
                            startProcess = { ExitedProcess(exitCode = 130) },
                        )

                    // Resolution runs before the spawn seam, so the command must exist on the host: the JVM's own
                    // binary does everywhere (and Darwin appends no suffix, so an absolute path is used as given).
                    runTest {
                        shouldThrow<CancellationException> { runner.run(listOf(hostExecutable())) }
                    }
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

/** A command that resolves on every host: the running JVM's own executable (resolution runs before the seam). */
private fun hostExecutable(): String =
    ProcessHandle
        .current()
        .info()
        .command()
        .orElseThrow()
