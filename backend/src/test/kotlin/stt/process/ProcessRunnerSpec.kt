@file:OptIn(ExperimentalCoroutinesApi::class)

package stt.process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.Integration
import kotlin.time.Duration
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
                    val outcome =
                        cancelAfterFirstLine(
                            ProcessRunner(gracePeriod = 300.milliseconds),
                            listOf("sh", "-c", "sleep 100 & echo \$\$ \$!; wait"),
                        )

                    outcome.thrown.shouldBeInstanceOf<CancellationException>()
                    outcome.aliveWhenRunThrew.shouldBe(emptyList())
                    outcome.elapsed.shouldBeLessThan(2.seconds)
                    outcome.pids.filter(::isAlive).shouldBeEmpty()
                }
            }
        }

        given("a running shell that ignores SIGTERM") {
            `when`("the coroutine running it is cancelled") {
                then("the shell is killed forcibly after the grace period and run throws CancellationException") {
                    val gracePeriod = 300.milliseconds
                    val outcome =
                        cancelAfterFirstLine(
                            ProcessRunner(gracePeriod),
                            listOf("sh", "-c", "trap '' TERM; echo \$\$; while :; do sleep 0.05; done"),
                        )

                    outcome.thrown.shouldBeInstanceOf<CancellationException>()
                    outcome.aliveWhenRunThrew.shouldBe(emptyList())
                    outcome.elapsed.shouldBeGreaterThanOrEqualTo(gracePeriod)
                    outcome.elapsed.shouldBeLessThan(2.seconds)
                }
            }
        }

        given("a counting shell loop") {
            `when`("the runner is paused and then resumed") {
                then("output freezes while paused and continues from the last delivered line after resume") {
                    val runner = ProcessRunner()
                    val delivered = mutableListOf<String>()
                    val sawThree = CompletableDeferred<Unit>()
                    var threshold = Int.MAX_VALUE
                    val nextAfterResume = CompletableDeferred<Int>()
                    val job =
                        launch {
                            runner.run(
                                listOf("sh", "-c", "i=0; while :; do i=\$((i+1)); echo \$i; sleep 0.05; done"),
                                onStdoutLine = { line ->
                                    underLock(delivered) { delivered.add(line) }
                                    if (line == "3") sawThree.complete(Unit)
                                    if (line.toInt() > threshold) nextAfterResume.complete(line.toInt())
                                },
                            )
                        }
                    try {
                        withTimeout(10.seconds) { sawThree.await() }
                        runner.pause()

                        val frozenCount = underLock(delivered) { delivered.size }
                        delay(500.milliseconds)
                        val windowCount = underLock(delivered) { delivered.size }
                        (windowCount - frozenCount).shouldBeLessThanOrEqualTo(1)

                        threshold = underLock(delivered) { delivered.last().toInt() }
                        runner.resume()

                        withTimeout(10.seconds) { nextAfterResume.await() }.shouldBe(threshold + 1)
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }

        given("a process tree stopped with SIGSTOP") {
            `when`("the coroutine running it is cancelled") {
                then("run terminates the tree within the grace period and throws CancellationException") {
                    val gracePeriod = 300.milliseconds
                    val runner = ProcessRunner(gracePeriod)
                    val outcome =
                        cancelAfterFirstLine(
                            runner,
                            listOf("sh", "-c", "sleep 100 & echo \$\$ \$!; wait"),
                            beforeCancel = { runner.pause() },
                        )

                    outcome.thrown.shouldBeInstanceOf<CancellationException>()
                    outcome.aliveWhenRunThrew.shouldBe(emptyList())
                    outcome.elapsed.shouldBeLessThan(gracePeriod)
                    outcome.pids.filter(::isAlive).shouldBeEmpty()
                }
            }
        }

        given("a command that does not exist") {
            `when`("running it") {
                then("run fails fast with an ExecutableNotFoundException naming the command") {
                    val exception =
                        withTimeout(2.seconds) {
                            shouldThrow<ExecutableNotFoundException> {
                                ProcessRunner().run(listOf("stt-no-such-binary"))
                            }
                        }

                    exception.message.shouldContain("stt-no-such-binary")
                }
            }
        }

        given("a runner whose run has already finished") {
            `when`("pause and resume are called on it") {
                then("they are no-ops") {
                    val runner = ProcessRunner()
                    val result =
                        withTimeout(10.seconds) {
                            runner.run(listOf("sh", "-c", "echo one"))
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(listOf("one"))

                    // The no-op contract: neither call throws, and the finished run is untouched.
                    runner.pause()
                    runner.resume()
                }
            }
        }

        given("a command whose last line has no trailing newline") {
            `when`("running it") {
                then("the final line is still delivered to callback and result") {
                    val delivered = mutableListOf<String>()

                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "printf 'one\\ntwo'"),
                                onStdoutLine = { delivered += it },
                            )
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(listOf("one", "two"))
                    delivered.shouldBe(listOf("one", "two"))
                }
            }
        }

        given("a command whose output is much larger than a pipe buffer") {
            `when`("running it") {
                then("run completes without deadlock and delivers every line") {
                    val delivered = mutableListOf<String>()

                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("seq", "1", "100000"),
                                onStdoutLine = { delivered += it },
                            )
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.size.shouldBe(100000)
                    result.stdout.first().shouldBe("1")
                    result.stdout[49999].shouldBe("50000")
                    result.stdout.last().shouldBe("100000")
                    delivered.size.shouldBe(100000)
                }
            }
        }

        given("a command with output still buffered when the run is cancelled") {
            `when`("the run is cancelled after the first line") {
                then("buffered lines are not delivered to the callback") {
                    val delivered = mutableListOf<String>()
                    val firstLine = CompletableDeferred<Unit>()
                    var thrown: Throwable? = null
                    val job =
                        launch {
                            try {
                                ProcessRunner(gracePeriod = 300.milliseconds).run(
                                    listOf("sh", "-c", "seq 1 100000; sleep 100"),
                                    onStdoutLine = { line ->
                                        delivered.add(line)
                                        firstLine.complete(Unit)
                                    },
                                )
                            } catch (e: CancellationException) {
                                thrown = e
                                throw e
                            }
                        }
                    try {
                        withTimeout(10.seconds) {
                            firstLine.await()
                            job.cancelAndJoin()
                        }
                    } finally {
                        job.cancelAndJoin()
                    }

                    thrown.shouldBeInstanceOf<CancellationException>()
                    delivered.size.shouldBeLessThan(20000)
                }
            }
        }

        given("a callback that throws") {
            `when`("running a command whose output triggers it") {
                then("run fails with that exception and kills the process tree") {
                    val pids = CompletableDeferred<List<Long>>()
                    var exception: IllegalStateException? = null
                    try {
                        exception =
                            withTimeout(2.seconds) {
                                shouldThrow<IllegalStateException> {
                                    ProcessRunner(gracePeriod = 300.milliseconds).run(
                                        listOf("sh", "-c", "echo \$\$; seq 1 100000; sleep 100"),
                                        onStdoutLine = { line ->
                                            pids.complete(parsePids(line))
                                            throw IllegalStateException("boom")
                                        },
                                    )
                                }
                            }
                    } finally {
                        if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)
                    }

                    exception.message.shouldBe("boom")
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

/**
 * Runs [runner] against [command], waits for the first line (the pid line) to arrive, invokes
 * [beforeCancel] (e.g. to freeze the tree for the stopped-cancel scenario), cancels the run from
 * the outside, then force-kills every reported pid. Returns what happened.
 */
private suspend fun cancelAfterFirstLine(
    runner: ProcessRunner,
    command: List<String>,
    beforeCancel: () -> Unit = {},
): CancelledRun {
    val pids = CompletableDeferred<List<Long>>()
    var thrown: CancellationException? = null
    var aliveWhenRunThrew: List<Long>? = null
    var elapsed: Duration = Duration.ZERO

    try {
        withTimeout(10.seconds) {
            val job =
                launch {
                    try {
                        runner.run(command, onStdoutLine = { line -> pids.complete(parsePids(line)) })
                    } catch (e: CancellationException) {
                        thrown = e
                        aliveWhenRunThrew = if (pids.isCompleted) pids.getCompleted().filter(::isAlive) else emptyList()
                        throw e
                    }
                }
            pids.await()
            beforeCancel()
            val cancelledAt = TimeSource.Monotonic.markNow()
            job.cancelAndJoin()
            elapsed = cancelledAt.elapsedNow()
        }
    } finally {
        if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)
    }

    return CancelledRun(thrown, aliveWhenRunThrew, elapsed, pids.getCompleted())
}

private class CancelledRun(
    val thrown: CancellationException?,
    val aliveWhenRunThrew: List<Long>?,
    val elapsed: Duration,
    val pids: List<Long>,
)

/**
 * The callback appends to `delivered` on an IO-dispatcher thread while the test coroutine counts
 * it, so every access goes through the list's lock.
 */
private fun <T> underLock(
    lock: Any,
    block: () -> T,
): T {
    synchronized(lock) { return block() }
}
