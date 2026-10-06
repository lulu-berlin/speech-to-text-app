package stt.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunner(
    private val gracePeriod: Duration = 2.seconds,
) {
    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ProcessResult =
        coroutineScope {
            val process = withContext(Dispatchers.IO) { ProcessBuilder(listOf("setsid") + command).start() }
            val group = ProcessGroup(process.pid())
            try {
                val stdout = async(Dispatchers.IO) { readLines(process.inputStream, onStdoutLine) }
                val stderr = async(Dispatchers.IO) { readLines(process.errorStream, onStderrLine) }
                val exitCode = awaitExit(process)
                ProcessResult(exitCode, stdout.await(), stderr.await())
            } catch (e: CancellationException) {
                withContext(NonCancellable) { group.terminate(gracePeriod) }
                throw e
            }
        }

    private fun readLines(
        stream: InputStream,
        onLine: (String) -> Unit,
    ): List<String> =
        stream.bufferedReader().useLines { lines ->
            lines.onEach(onLine).toList()
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
