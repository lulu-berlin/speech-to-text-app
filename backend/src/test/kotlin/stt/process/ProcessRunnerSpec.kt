package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.Integration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProcessRunnerSpec :
    BehaviorSpec({
        tags(Integration)

        given("a command that prints three lines and exits successfully") {
            `when`("running it") {
                then("the exit code is 0 and each stdout line reaches the callback and the result in order") {
                    val expectedLines = listOf("one", "two", "three")
                    val delivered = mutableListOf<String>()

                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "echo one; echo two; echo three"),
                                onStdoutLine = { delivered += it },
                            )
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(expectedLines)
                    delivered.shouldBe(expectedLines)
                }
            }
        }

        given("a command that writes two stderr lines and exits with code 3") {
            `when`("running it") {
                then("the result carries exit code 3 and the stderr lines in order") {
                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "echo first-error >&2; echo second-error >&2; exit 3"),
                            )
                        }

                    result.exitCode.shouldBe(3)
                    result.stderr.shouldBe(listOf("first-error", "second-error"))
                }
            }
        }

        given("a command that writes two stderr lines") {
            `when`("running it with an onStderrLine callback") {
                then("each stderr line reaches the callback in order") {
                    val delivered = mutableListOf<String>()

                    withTimeout(10.seconds) {
                        ProcessRunner().run(
                            listOf("sh", "-c", "echo first-error >&2; echo second-error >&2"),
                            onStderrLine = { delivered += it },
                        )
                    }

                    delivered.shouldBe(listOf("first-error", "second-error"))
                }
            }
        }

        given("a running shell whose background child is sleep 100") {
            `when`("the coroutine running it is cancelled") {
                then("run throws CancellationException promptly, and only after the whole tree is dead") {
                    val pids = CompletableDeferred<List<Long>>()
                    var thrown: Throwable? = null
                    var aliveWhenRunThrew: List<Long>? = null

                    try {
                        withTimeout(10.seconds) {
                            val job =
                                launch {
                                    try {
                                        ProcessRunner(gracePeriod = 300.milliseconds).run(
                                            listOf("sh", "-c", "sleep 100 & echo \$\$ \$!; wait"),
                                            onStdoutLine = { line -> pids.complete(parsePids(line)) },
                                        )
                                    } catch (e: CancellationException) {
                                        thrown = e
                                        aliveWhenRunThrew = pids.getCompleted().filter(::isAlive)
                                        throw e
                                    }
                                }
                            pids.await()
                            val cancelledAt = TimeSource.Monotonic.markNow()
                            job.cancelAndJoin()
                            cancelledAt.elapsedNow().shouldBeLessThan(2.seconds)
                        }
                    } finally {
                        if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)
                    }

                    thrown.shouldBeInstanceOf<CancellationException>()
                    aliveWhenRunThrew.shouldBe(emptyList())
                    pids.getCompleted().filter(::isAlive).shouldBeEmpty()
                }
            }
        }
    })

/** Parses a line of space-separated pids, as printed by `echo $$ $!`. */
private fun parsePids(line: String): List<Long> = line.split(" ").map(String::toLong)

private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

private fun killForcibly(pid: Long) {
    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
}
