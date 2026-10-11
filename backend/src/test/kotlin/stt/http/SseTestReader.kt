package stt.http

import java.io.BufferedReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads SSE frames ("event: …\ndata: …\n\n") from the JDK HTTP client's stream: one frame per call, with a
 * 5-second deadline per line, so a closed or stalled stream fails the test instead of hanging it.
 */
internal class SseTestReader(
    private val reader: BufferedReader,
) : AutoCloseable {
    private val pool = Executors.newSingleThreadExecutor()

    /** Drops this subscriber's connection, as a closed browser tab does. */
    override fun close() {
        reader.close()
        pool.shutdownNow()
    }

    fun readEvent(): Pair<String, String> {
        var name: String? = null
        val data = StringBuilder()
        while (true) {
            val line =
                pool
                    .submit<String?>(Callable<String?> { reader.readLine() })
                    .get(5, TimeUnit.SECONDS)
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
