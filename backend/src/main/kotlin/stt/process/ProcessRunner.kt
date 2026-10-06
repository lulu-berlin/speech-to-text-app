package stt.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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

class ProcessRunner(
    private val gracePeriod: Duration = 2.seconds,
) {
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
            val process = withContext(Dispatchers.IO) { ProcessBuilder(listOf("setsid", executable) + command.drop(1)).start() }
            val group = ProcessGroup(process.pid())
            currentGroup.set(group)
            val stdout = async(Dispatchers.IO) { readLines(process.inputStream, onStdoutLine) }
            val stderr = async(Dispatchers.IO) { readLines(process.errorStream, onStderrLine) }
            try {
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
     * Reads [stream] line by line and delivers each to [onLine]. The coroutineScope is the
     * receiver for the per-line ensureActive(): without it the cancellation check has no
     * CoroutineContext to observe.
     */
    private suspend fun readLines(
        stream: InputStream,
        onLine: (String) -> Unit,
    ): List<String> =
        coroutineScope {
            stream.bufferedReader().useLines { lines ->
                lines
                    .onEach { line ->
                        ensureActive()
                        onLine(line)
                    }.toList()
            }
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
