package stt.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunner internal constructor(
    private val gracePeriod: Duration,
    /** Starts the built process. A seam so tests can act inside the spawn window (ADR 0020). */
    private val startProcess: (ProcessBuilder) -> Process,
) : ProcessControl {
    constructor(gracePeriod: Duration = 2.seconds) : this(gracePeriod, ProcessBuilder::start)

    /**
     * Guards the per-run state ([inFlight], [currentTree], [paused]). Registering a tree and applying
     * a pending pause is one step relative to pause()/resume(), so a resume() can never be overtaken by
     * a stale SIGSTOP; ending a run clears the pause and releases the run in one step.
     */
    private val runLock = Any()

    /** Single-flight guard (ADR 0020): claimed when a run starts, released only by that run when it ends. */
    private var inFlight = false

    /**
     * The process tree of the single in-flight run. set when spawning finishes, cleared when the run does;
     * lets the non-suspending pause()/resume() reach the current run without blocking.
     */
    private var currentTree: ProcessTree? = null

    /** Per-run pause flag (ADR 0020): a pause() before the group exists is applied right after spawn. */
    private var paused = false

    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ProcessResult {
        synchronized(runLock) {
            check(!inFlight) { "A run is already in flight: ProcessRunner is single-flight" }
            inFlight = true
        }
        try {
            return runExclusively(command, onStdoutLine, onStderrLine)
        } finally {
            // Together under the lock: a pause() racing the end of this run cannot re-arm the flag
            // after it was cleared, so a leftover pause never freezes the next run (ADR 0020).
            synchronized(runLock) {
                paused = false
                inFlight = false
            }
        }
    }

    private suspend fun runExclusively(
        command: List<String>,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
    ): ProcessResult {
        val executable = withContext(Dispatchers.IO) { resolveInPath(command[0]) }
        return coroutineScope {
            val process = spawn(listOf("setsid", executable) + command.drop(1))
            val tree = ProcessTree(process.pid(), process::isAlive)
            synchronized(runLock) {
                currentTree = tree
                if (paused) tree.pause()
            }
            val stdout = async(Dispatchers.IO) { readLines(process.inputStream, process, onStdoutLine) }
            val stderr = async(Dispatchers.IO) { readLines(process.errorStream, process, onStderrLine) }
            try {
                // A cancel that landed during spawn surfaces here, where the failure path kills the tree.
                ensureActive()
                val exitCode = awaitExit(process)
                // A SIGTERM death is an interruption, not a failure: shutdown or an external TERM killed
                // the tree while the caller waited (roadmap item 34, discovered). Surface it as a
                // cancellation so the engine never records FAILED: it puts the task back to QUEUED and runs it
                // again (item 42), or, when the app is quitting, leaves it to the next start's recovery
                // (ADR 0004) — this covers whisper-cli and ffmpeg alike.
                if (exitCode == SIGTERM_EXIT_CODE) {
                    throw CancellationException("process was terminated by SIGTERM (exit $SIGTERM_EXIT_CODE)")
                }
                ProcessResult(exitCode, stdout.await(), stderr.await())
            } catch (e: Throwable) {
                // Any failure, not only cancellation: a callback that throws after the direct child
                // exited surfaces from await() and must still kill the surviving tree (ADR 0020).
                stdout.cancel()
                stderr.cancel()
                withContext(NonCancellable) {
                    tree.terminate(gracePeriod)
                    stdout.cancelAndJoin()
                    stderr.cancelAndJoin()
                }
                throw e
            } finally {
                synchronized(runLock) { currentTree = null }
            }
        }
    }

    /** Freezes the current run (SIGSTOP the whole tree), also when it is still spawning. No-op while no run is in flight. */
    override fun pause() {
        synchronized(runLock) {
            if (!inFlight) return
            paused = true
            currentTree?.pause()
        }
    }

    /** Unfreezes the current run (SIGCONT the whole tree). No-op while no run is in flight. */
    override fun resume() {
        synchronized(runLock) {
            paused = false
            currentTree?.resume()
        }
    }

    /**
     * Starts [command] so the started process is never lost. A plain withContext discards its result
     * when the caller is cancelled while start() runs (prompt cancellation on the way back); the outer
     * NonCancellable context returns it regardless, so the caller can register and kill it (ADR 0020).
     *
     * stdin is /dev/null, not a pipe nobody writes or closes: a tool that reads stdin to EOF (ffmpeg
     * without -nostdin) would otherwise wait forever.
     */
    private suspend fun spawn(command: List<String>): Process {
        val builder = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) { startProcess(builder) }
        }
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

    /** A blocking waitFor cannot be cancelled, so the wait is sliced to observe cancellation. */
    private suspend fun awaitExit(process: Process): Int =
        withContext(Dispatchers.IO) {
            while (!process.waitFor(POLL_INTERVAL.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                ensureActive()
            }
            process.exitValue()
        }

    private companion object {
        /** The exit code of a process killed by SIGTERM: 128 + 15, Linux convention (as in ProcessTree). */
        const val SIGTERM_EXIT_CODE = 143
    }
}

/** Interval at which blocking waits (exit, tree emptiness) re-check their condition and cancellation. */
internal val POLL_INTERVAL = 50.milliseconds
