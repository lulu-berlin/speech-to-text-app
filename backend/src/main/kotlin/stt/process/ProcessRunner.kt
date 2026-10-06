package stt.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunner internal constructor(
    private val gracePeriod: Duration,
    /** Starts the built process. A seam so tests can act inside the spawn window (ADR 0020). */
    private val startProcess: (ProcessBuilder) -> Process,
) {
    constructor(gracePeriod: Duration = 2.seconds) : this(gracePeriod, ProcessBuilder::start)

    /**
     * The group of the single in-flight run. set when spawning finishes, cleared when the run does;
     * lets the non-suspending pause()/resume() reach the current run without blocking.
     */
    private val currentGroup = AtomicReference<ProcessGroup?>(null)

    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ProcessResult {
        val executable = withContext(Dispatchers.IO) { resolveInPath(command[0]) }
        return coroutineScope {
            val process = spawn(listOf("setsid", executable) + command.drop(1))
            val group = ProcessGroup(process.pid())
            currentGroup.set(group)
            val stdout = async(Dispatchers.IO) { readLines(process.inputStream, process, onStdoutLine) }
            val stderr = async(Dispatchers.IO) { readLines(process.errorStream, process, onStderrLine) }
            try {
                // A cancel that landed during spawn surfaces here, where the failure path kills the group.
                ensureActive()
                val exitCode = awaitExit(process)
                ProcessResult(exitCode, stdout.await(), stderr.await())
            } catch (e: CancellationException) {
                stdout.cancel()
                stderr.cancel()
                withContext(NonCancellable) {
                    group.terminate(gracePeriod)
                    stdout.cancelAndJoin()
                    stderr.cancelAndJoin()
                }
                throw e
            } finally {
                currentGroup.set(null)
            }
        }
    }

    /** Freezes the current run (SIGSTOP the whole tree). No-op while no run is in flight. */
    fun pause() {
        currentGroup.get()?.pause()
    }

    /** Unfreezes the current run (SIGCONT the whole tree). No-op while no run is in flight. */
    fun resume() {
        currentGroup.get()?.resume()
    }

    /**
     * Starts [command] so the started process is never lost. A plain withContext discards its result
     * when the caller is cancelled while start() runs (prompt cancellation on the way back); the outer
     * NonCancellable context returns it regardless, so the caller can register and kill it (ADR 0020).
     */
    private suspend fun spawn(command: List<String>): Process =
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { startProcess(ProcessBuilder(command)) }
        }

    /** Fails fast on a missing executable: resolves [name] to an absolute existing executable, or throws. */
    private fun resolveInPath(name: String): String =
        pathCandidates(name).firstOrNull { isExecutable(it) }
            ?: throw ExecutableNotFoundException("Executable not found: $name")

    private fun pathCandidates(name: String): List<String> =
        if (name.contains('/')) {
            listOf(name)
        } else {
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.startsWith("/") }
                .map { dir -> "$dir/$name" }
                .toList()
        }

    private fun isExecutable(path: String): Boolean {
        val file = Path.of(path)
        return Files.isRegularFile(file) && Files.isExecutable(file)
    }

    /**
     * Reads [stream] line by line and delivers each to [onLine].
     *
     * Reads only when the pipe reports bytes available, so a blocked pipe (a descendant that left
     * the process group and holds it open) never blocks the drain: the loop exits when the direct
     * [process] is dead and nothing is buffered, and cancellation lands at the poll checkpoints.
     */
    private suspend fun readLines(
        stream: InputStream,
        process: Process,
        onLine: (String) -> Unit,
    ): List<String> {
        val context = currentCoroutineContext()
        val lines = mutableListOf<String>()
        val chunk = ByteArray(READ_CHUNK_SIZE)
        var carry = ByteArray(READ_CHUNK_SIZE)
        var carryLen = 0
        while (true) {
            context.ensureActive()
            if (stream.available() > 0) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (carryLen + n > carry.size) carry = carry.copyOf(carryLen + n + READ_CHUNK_SIZE)
                System.arraycopy(chunk, 0, carry, carryLen, n)
                carryLen += n
                var start = 0
                var consumed = 0
                for (i in 0 until carryLen) {
                    if (carry[i] == LINE_FEED) {
                        // Per line, not per chunk: no line reaches a callback after a cancel (ADR 0020).
                        context.ensureActive()
                        val line = String(carry, start, i - start, Charsets.UTF_8)
                        lines.add(line)
                        onLine(line)
                        start = i + 1
                        consumed = start
                    }
                }
                if (consumed > 0) {
                    System.arraycopy(carry, consumed, carry, 0, carryLen - consumed)
                    carryLen -= consumed
                }
            } else if (!process.isAlive()) {
                break
            } else {
                delay(POLL_INTERVAL)
            }
        }
        if (carryLen > 0) {
            context.ensureActive()
            val line = String(carry, 0, carryLen, Charsets.UTF_8)
            lines.add(line)
            onLine(line)
        }
        return lines
    }

    /** A blocking waitFor cannot be cancelled, so the wait is sliced to observe cancellation. */
    private suspend fun awaitExit(process: Process): Int =
        withContext(Dispatchers.IO) {
            while (!process.waitFor(POLL_INTERVAL.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                ensureActive()
            }
            process.exitValue()
        }
}

/** Interval at which blocking waits (exit, group emptiness) re-check their condition and cancellation. */
internal val POLL_INTERVAL = 50.milliseconds

/** Drain chunk size; also the initial carry buffer size. */
private const val READ_CHUNK_SIZE = 8192

/** Byte value of the line terminator. */
private const val LINE_FEED = '\n'.code.toByte()
