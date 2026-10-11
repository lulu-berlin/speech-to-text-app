package stt.http

import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads SSE frames ("event: …\ndata: …\n\n") from the JDK HTTP client's stream: one frame per call, with a
 * deadline per line (5 seconds unless the caller waits for something slower, like a heartbeat), so a closed or
 * stalled stream fails the test instead of hanging it.
 */
internal class SseTestReader(
    private val stream: InputStream,
) : AutoCloseable {
    private val reader = stream.bufferedReader()
    private val pool = Executors.newSingleThreadExecutor()

    /**
     * Drops this subscriber's connection, as a closed browser tab does. Closes the raw stream, not the reader: a
     * read that timed out still holds the reader's lock inside `readLine`, and closing the reader would wait on it.
     */
    override fun close() {
        stream.close()
        pool.shutdownNow()
    }

    fun readEvent(lineTimeout: Duration = 5.seconds): Pair<String, String> {
        var name: String? = null
        val data = StringBuilder()
        while (true) {
            val line =
                pool
                    .submit<String?>(Callable<String?> { reader.readLine() })
                    .get(lineTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    ?: error("the SSE stream closed")
            when {
                line.isEmpty() -> {
                    return name!! to data.toString()
                }

                line.startsWith("event: ") -> {
                    name = line.removePrefix("event: ")
                }

                line.startsWith("data: ") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data: "))
                }
            }
        }
    }
}
