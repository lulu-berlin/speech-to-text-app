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
    /** The process environment; a seam so a test can exercise Windows PATH resolution on any host. */
    private val env: Map<String, String> = System.getenv(),
    /** The host platform's signals and process-group support; production uses the JVM's `os.name`. */
    private val platform: ProcessPlatform = ProcessPlatform.current(),
    /** Whether the backend is stopping (the known-stopped-by-us flag, item 49). */
    private val stopping: () -> Boolean = { false },
    /** Starts the built process. A seam so tests can act inside the spawn window (ADR 0020). */
    private val startProcess: (ProcessBuilder) -> Process,
) : ProcessControl {
    /** [stopping] tells the runner the backend is shutting down; production wires it to the application's stop. */
    constructor(
        gracePeriod: Duration = 2.seconds,
        stopping: () -> Boolean = { false },
    ) : this(gracePeriod, stopping = stopping, startProcess = ProcessBuilder::start)

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
            // Linux owns a process group through setsid; Darwin has no setsid, so the run is the
            // direct child alone and its signals target that pid. Windows has neither: the direct
            // child is the whole run and is destroyed directly.
            val launch = if (platform.processGroups) listOf("setsid", executable) else listOf(executable)
            val process = spawn(launch + command.drop(1))
            val tree = if (platform.signals) ProcessTree(process.pid(), process::isAlive, platform) else null
            synchronized(runLock) {
                currentTree = tree
                if (paused) tree?.pause()
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
                if (platform.signals && exitCode == SIGTERM_EXIT_CODE) {
                    throw CancellationException("process was terminated by SIGTERM (exit $SIGTERM_EXIT_CODE)")
                }
                // A console Ctrl-C (stt.cmd) reaches the tool and the JVM at once, so the tool can end with
                // STATUS_CONTROL_C_EXIT before the shutdown has set [stopping]: Windows' counterpart of exit 143.
                if (platform.windows && exitCode == STATUS_CONTROL_C_EXIT) {
                    throw CancellationException("process was ended by a console Ctrl-C (STATUS_CONTROL_C_EXIT)")
                }
                // The known-stopped-by-us flag (item 49): a tool that dies while the backend is stopping was
                // stopped by the shutdown — on Windows there is no SIGTERM to recognise it by — so it is an
                // interruption on every platform, never a failure.
                if (exitCode != 0 && stopping()) {
                    throw CancellationException("process exited with $exitCode while the backend was stopping")
                }
                ProcessResult(exitCode, stdout.await(), stderr.await())
            } catch (e: Throwable) {
                // Any failure, not only cancellation: a callback that throws after the direct child
                // exited surfaces from await() and must still kill the surviving tree (ADR 0020).
                stdout.cancel()
                stderr.cancel()
                withContext(NonCancellable) {
                    if (tree != null) tree.terminate(gracePeriod) else terminateDirectly(process)
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
        // Windows has no signal that freezes a process: the pause gate stops the run before its next chunk.
        if (!platform.signals) return
        synchronized(runLock) {
            if (!inFlight) return
            paused = true
            currentTree?.pause()
        }
    }

    /** Unfreezes the current run (SIGCONT the whole tree). No-op while no run is in flight. */
    override fun resume() {
        if (!platform.signals) return
        synchronized(runLock) {
            paused = false
            currentTree?.resume()
        }
    }

    /** Windows: there is no signal to send, so the run's direct child is terminated and waited for, bounded. */
    private suspend fun terminateDirectly(process: Process) {
        process.destroyForcibly()
        withContext(Dispatchers.IO) {
            process.waitFor(gracePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Starts [command] so the started process is never lost. A plain withContext discards its result
     * when the caller is cancelled while start() runs (prompt cancellation on the way back); the outer
     * NonCancellable context returns it regardless, so the caller can register and kill it (ADR 0020).
     *
     * stdin is the null device (/dev/null, NUL on Windows), not a pipe nobody writes or closes: a
     * tool that reads stdin to EOF (ffmpeg without -nostdin) would otherwise wait forever.
     */
    private suspend fun spawn(command: List<String>): Process {
        val builder = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File(platform.nullDevice)))
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) { startProcess(builder) }
        }
    }

    /** Fails fast on a missing executable: resolves [name] to an absolute existing executable, or throws. */
    private fun resolveInPath(name: String): String =
        pathCandidates(name).firstOrNull { isExecutable(it) }
            ?: throw ExecutableNotFoundException("Executable not found: $name")

    /**
     * The candidate paths for [name]: the name itself when it is a path, otherwise every absolute
     * directory of [env]'s PATH joined with the name. On Windows PATH is `;`-separated and a bare
     * tool name is tried as given and with `.exe` (whisper-cli, ffmpeg), the way CreateProcess does.
     */
    private fun pathCandidates(name: String): List<String> {
        if (name.contains('/') || name.contains('\\')) return listOf(name)
        val separator = if (platform.windows) ';' else ':'
        val suffixes =
            if (platform.windows && !name.endsWith(".exe", ignoreCase = true)) listOf("", ".exe") else listOf("")
        return (searchPath() ?: "")
            .split(separator)
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() && Path.of(it).isAbsolute }
            .flatMap { dir -> suffixes.map { suffix -> Path.of(dir, name + suffix).toString() } }
    }

    /**
     * The PATH variable. Windows names are case-insensitive and the system spells this one `Path`,
     * but the map [System.getenv] returns there is case-sensitive, so the name is matched ignoring case.
     */
    private fun searchPath(): String? =
        if (platform.windows) {
            env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
        } else {
            env["PATH"]
        }

    private fun isExecutable(path: String): Boolean {
        val file = Path.of(path)
        // Windows has no executable bit on a regular file: existence is what CreateProcess needs.
        return Files.isRegularFile(file) && (platform.windows || Files.isExecutable(file))
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

        /** The exit code of a Windows process ended by a console Ctrl-C: NTSTATUS STATUS_CONTROL_C_EXIT. */
        const val STATUS_CONTROL_C_EXIT = 0xC000013A.toInt()
    }
}

/** Interval at which blocking waits (exit, tree emptiness) re-check their condition and cancellation. */
internal val POLL_INTERVAL = 50.milliseconds
