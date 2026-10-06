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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.Integration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
                    val threshold = AtomicInteger(Int.MAX_VALUE)
                    val nextAfterResume = CompletableDeferred<Int>()
                    val job =
                        launch {
                            runner.run(
                                countingLoop(),
                                onStdoutLine = { line ->
                                    underLock(delivered) { delivered.add(line) }
                                    if (line == "3") sawThree.complete(Unit)
                                    if (line.toInt() > threshold.get()) nextAfterResume.complete(line.toInt())
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

                        threshold.set(underLock(delivered) { delivered.last().toInt() })
                        runner.resume()

                        withTimeout(10.seconds) { nextAfterResume.await() }.shouldBe(threshold.get() + 1)
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
            `when`("the run is cancelled while the first line is being delivered") {
                then("no further line reaches the callback") {
                    val delivered = mutableListOf<String>()
                    val firstLine = CompletableDeferred<Unit>()
                    // Holds the first callback until the cancel has been issued, so every later line is after it.
                    val cancelIssued = CountDownLatch(1)
                    var thrown: Throwable? = null
                    val job =
                        launch {
                            try {
                                ProcessRunner(gracePeriod = 300.milliseconds).run(
                                    listOf("sh", "-c", "seq 1 100000; sleep 100"),
                                    onStdoutLine = { line ->
                                        delivered.add(line)
                                        firstLine.complete(Unit)
                                        cancelIssued.await(10, TimeUnit.SECONDS)
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
                            job.cancel()
                            cancelIssued.countDown()
                            job.join()
                        }
                    } finally {
                        cancelIssued.countDown()
                        job.cancelAndJoin()
                    }

                    thrown.shouldBeInstanceOf<CancellationException>()
                    delivered.shouldBe(listOf("1"))
                }
            }
        }

        given("a command that copies its stdin to stdout until EOF, then prints done") {
            `when`("running it") {
                then("it sees an empty stdin at EOF, finishes, and prints only done") {
                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(listOf("sh", "-c", "cat; echo done"))
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(listOf("done"))
                }
            }
        }

        given("a callback that throws") {
            `when`("running a command whose output triggers it") {
                then("run fails with that exception and kills the process tree") {
                    val pids = CompletableDeferred<List<Long>>()
                    var exception: IllegalStateException? = null
                    var aliveWhenRunThrew: List<Long>? = null
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
                        // Checked before the cleanup below, which force-kills every pid.
                        aliveWhenRunThrew = pids.getCompleted().filter(::isAlive)
                    } finally {
                        if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)
                    }

                    exception.message.shouldBe("boom")
                    aliveWhenRunThrew.shouldBe(emptyList())
                }
            }
        }

        given("a callback that throws after the direct child has exited") {
            `when`("a descendant still runs in the process group") {
                then("run fails with that exception and the descendant is already dead") {
                    val pids = CompletableDeferred<List<Long>>()
                    var thrown: IllegalStateException? = null
                    var aliveWhenRunThrew: List<Long>? = null
                    try {
                        withTimeout(10.seconds) {
                            try {
                                ProcessRunner(gracePeriod = 300.milliseconds).run(
                                    listOf("sh", "-c", "sleep 100 & echo \$\$ \$!"),
                                    onStdoutLine = { line ->
                                        val shell = parsePids(line).also(pids::complete).first()
                                        ProcessHandle.of(shell).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
                                        // run sees the exit within milliseconds, but no observable event marks
                                        // it having moved past the exit wait; the margin covers that step.
                                        Thread.sleep(500)
                                        throw IllegalStateException("boom")
                                    },
                                )
                            } catch (e: IllegalStateException) {
                                thrown = e
                                aliveWhenRunThrew = pids.getCompleted().filter(::isAlive)
                            }
                        }
                    } finally {
                        if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)
                    }

                    thrown?.message.shouldBe("boom")
                    aliveWhenRunThrew.shouldBe(emptyList())
                }
            }
        }

        given("a descendant that left the process group and still holds the output pipes") {
            `when`("the run is cancelled") {
                then("run returns promptly with CancellationException") {
                    val outcome =
                        cancelAfterFirstLine(
                            ProcessRunner(gracePeriod = 300.milliseconds),
                            listOf("sh", "-c", "setsid sleep 1000 & echo \$\$ \$!; wait"),
                        )

                    outcome.thrown.shouldBeInstanceOf<CancellationException>()
                    outcome.elapsed.shouldBeLessThan(2.seconds)
                    outcome.aliveWhenRunThrew.shouldBe(listOf(outcome.pids[1]))
                }
            }
        }

        given("a run whose caller is cancelled while the process is being spawned") {
            `when`("the process start returns after the cancel") {
                then("run throws CancellationException and the spawned process is already dead") {
                    lateinit var job: Job
                    val spawned = CompletableDeferred<Process>()
                    val runner =
                        ProcessRunner(300.milliseconds) { builder ->
                            builder.start().also { process ->
                                spawned.complete(process)
                                job.cancel()
                            }
                        }
                    var thrown: Throwable? = null
                    var aliveWhenRunThrew: Boolean? = null
                    try {
                        withTimeout(10.seconds) {
                            job =
                                launch(start = CoroutineStart.LAZY) {
                                    try {
                                        runner.run(listOf("sleep", "100"))
                                    } catch (e: CancellationException) {
                                        thrown = e
                                        aliveWhenRunThrew = isAlive(spawned.getCompleted().pid())
                                        throw e
                                    }
                                }
                            job.start()
                            job.join()
                        }
                    } finally {
                        if (spawned.isCompleted) killForcibly(spawned.getCompleted().pid())
                    }

                    thrown.shouldBeInstanceOf<CancellationException>()
                    aliveWhenRunThrew.shouldBe(false)
                }
            }
        }

        given("a run whose caller is cancelled before the process group exists") {
            `when`("setsid creates the group only after the cancel") {
                then("run throws CancellationException and the spawned process is already dead") {
                    lateinit var job: Job
                    val spawned = CompletableDeferred<Process>()
                    val runner =
                        ProcessRunner(300.milliseconds) { builder ->
                            delayGroupCreation(builder).start().also { process ->
                                spawned.complete(process)
                                job.cancel()
                            }
                        }
                    var thrown: Throwable? = null
                    var aliveWhenRunThrew: Boolean? = null
                    try {
                        withTimeout(10.seconds) {
                            job =
                                launch(start = CoroutineStart.LAZY) {
                                    try {
                                        runner.run(listOf("sleep", "100"))
                                    } catch (e: CancellationException) {
                                        thrown = e
                                        aliveWhenRunThrew = isAlive(spawned.getCompleted().pid())
                                        throw e
                                    }
                                }
                            job.start()
                            job.join()
                        }
                    } finally {
                        if (spawned.isCompleted) killForcibly(spawned.getCompleted().pid())
                    }

                    thrown.shouldBeInstanceOf<CancellationException>()
                    aliveWhenRunThrew.shouldBe(false)
                }
            }
        }

        given("a runner with a counting loop in flight") {
            `when`("a second run is started on the same runner") {
                then("the second run fails fast with IllegalStateException and the first run stays pausable") {
                    val runner = ProcessRunner()
                    val delivered = mutableListOf<String>()
                    val sawThree = CompletableDeferred<Unit>()
                    val job =
                        launch {
                            runner.run(
                                countingLoop(),
                                onStdoutLine = { line ->
                                    underLock(delivered) { delivered.add(line) }
                                    if (line == "3") sawThree.complete(Unit)
                                },
                            )
                        }
                    try {
                        withTimeout(10.seconds) { sawThree.await() }

                        withTimeout(2.seconds) {
                            shouldThrow<IllegalStateException> {
                                runner.run(listOf("sh", "-c", "echo second"))
                            }
                        }

                        runner.pause()
                        val frozenCount = underLock(delivered) { delivered.size }
                        delay(500.milliseconds)
                        val windowCount = underLock(delivered) { delivered.size }
                        (windowCount - frozenCount).shouldBeLessThanOrEqualTo(1)
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }

        given("a pause() issued while the process is being spawned") {
            `when`("the run starts") {
                then("the process produces no output until resume(), then starts from its first line") {
                    val run =
                        pausedFromSpawn(
                            start = ProcessBuilder::start,
                            // The 0.5 s head start keeps output from racing the SIGSTOP right after spawn.
                            command = countingLoop(headStart = 500.milliseconds),
                        )

                    run.deliveredWhilePaused.shouldBeEmpty()
                    run.firstLineAfterResume.shouldBe("1")
                }
            }
        }

        given("a pause() issued before the process group exists") {
            `when`("setsid creates the group only after the pause") {
                then("the process produces no output until resume(), then starts from its first line") {
                    val run = pausedFromSpawn(start = { delayGroupCreation(it).start() }, command = countingLoop())

                    run.deliveredWhilePaused.shouldBeEmpty()
                    run.firstLineAfterResume.shouldBe("1")
                }
            }
        }

        given("a runner whose previous run was paused and then cancelled") {
            `when`("the next run starts on the same runner") {
                then("it is not frozen: it finishes and delivers its output") {
                    val runner = ProcessRunner(300.milliseconds)
                    val sawThree = CompletableDeferred<Unit>()
                    val first =
                        launch {
                            runner.run(countingLoop(), onStdoutLine = { if (it == "3") sawThree.complete(Unit) })
                        }
                    try {
                        withTimeout(10.seconds) { sawThree.await() }
                        runner.pause()
                    } finally {
                        first.cancelAndJoin()
                    }

                    val result =
                        withTimeout(5.seconds) {
                            runner.run(listOf("sh", "-c", "echo second"))
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(listOf("second"))
                }
            }
        }
    })

/** A shell loop that prints 1, 2, 3, … every 50 ms, after [headStart] of silence. */
private fun countingLoop(headStart: Duration = Duration.ZERO): List<String> {
    val delay = if (headStart > Duration.ZERO) "sleep ${headStart.inWholeMilliseconds / 1000.0}; " else ""
    return listOf("sh", "-c", "${delay}i=0; while :; do i=\$((i+1)); echo \$i; sleep 0.05; done")
}

/**
 * Delays the creation of the run's process group by 0.5 s: the built `setsid <cmd…>` is started through
 * `sh -c 'sleep 0.5; exec "$@"'`, so the direct child stays in the JVM's group until it execs setsid.
 * Lets a test land a pause() or a cancel while the group does not exist yet (ADR 0020).
 */
private fun delayGroupCreation(builder: ProcessBuilder): ProcessBuilder =
    builder.command(listOf("sh", "-c", "sleep 0.5; exec \"\$@\"", "sh") + builder.command())

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
 * Runs [command] on a runner that is paused from inside its process start, which starts the built
 * process with [start]; waits 1 s after the start, records what was delivered, then resumes and
 * waits for the first line. Cancels the run either way.
 */
private suspend fun pausedFromSpawn(
    start: (ProcessBuilder) -> Process,
    command: List<String>,
): PausedRun =
    coroutineScope {
        lateinit var runner: ProcessRunner
        val spawned = CompletableDeferred<Unit>()
        runner =
            ProcessRunner(2.seconds) { builder ->
                runner.pause()
                start(builder).also { spawned.complete(Unit) }
            }
        val delivered = mutableListOf<String>()
        val firstLine = CompletableDeferred<String>()
        val job =
            launch {
                runner.run(
                    command,
                    onStdoutLine = { line ->
                        underLock(delivered) { delivered.add(line) }
                        firstLine.complete(line)
                    },
                )
            }
        try {
            withTimeout(10.seconds) { spawned.await() }
            delay(1.seconds)
            val deliveredWhilePaused = underLock(delivered) { delivered.toList() }
            runner.resume()
            PausedRun(deliveredWhilePaused, withTimeout(10.seconds) { firstLine.await() })
        } finally {
            job.cancelAndJoin()
        }
    }

private class PausedRun(
    val deliveredWhilePaused: List<String>,
    val firstLineAfterResume: String,
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
