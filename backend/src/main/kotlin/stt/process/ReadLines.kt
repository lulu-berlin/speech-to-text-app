package stt.process

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/**
 * Reads [stream] line by line and delivers each to [onLine].
 *
 * Reads only when the pipe reports bytes available, so a blocked pipe (a descendant that left
 * the process group and holds it open) never blocks the drain: the loop exits when the direct
 * [process] is dead and nothing is buffered, and cancellation lands at the poll checkpoints.
 */
internal suspend fun readLines(
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
            // Doubling keeps a long line without '\n' linear in its length (amortized copies).
            if (carryLen + n > carry.size) carry = carry.copyOf(maxOf(carry.size * 2, carryLen + n))
            System.arraycopy(chunk, 0, carry, carryLen, n)
            // The carried-over bytes hold no '\n' (every line up to the last one was consumed), so
            // only the new bytes need scanning.
            val scanFrom = carryLen
            carryLen += n
            var start = 0
            for (i in scanFrom until carryLen) {
                if (carry[i] == LINE_FEED) {
                    // Per line, not per chunk: no line reaches a callback after a cancel (ADR 0020).
                    context.ensureActive()
                    val line = String(carry, start, i - start, Charsets.UTF_8)
                    lines.add(line)
                    onLine(line)
                    start = i + 1
                }
            }
            if (start > 0) {
                System.arraycopy(carry, start, carry, 0, carryLen - start)
                carryLen -= start
            }
        } else if (process.isAlive()) {
            delay(POLL_INTERVAL)
        } else if (stream.available() == 0) {
            // Re-checked after the child is seen dead: its final write may have landed between the
            // first check and isAlive(); if so, the next iteration reads it before the loop ends.
            break
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

/** Drain chunk size; also the initial carry buffer size. */
private const val READ_CHUNK_SIZE = 8192

/** Byte value of the line terminator. */
private const val LINE_FEED = '\n'.code.toByte()
