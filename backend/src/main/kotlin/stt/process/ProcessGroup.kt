package stt.process

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The process group of one run: the pid of a `setsid`-spawned child is its group id, and
 * kill(2) with the negated id reaches every process in the tree (ADR 0020). Linux signal numbers.
 */
internal class ProcessGroup(
    id: Long,
) {
    /** kill(2) addresses a whole group through the negated group id. */
    private val target = -id.toInt()

    /** SIGCONT first so a stopped tree can act on SIGTERM; SIGKILL if it outlives the grace period. */
    suspend fun terminate(gracePeriod: Duration) {
        signal(SIGCONT)
        signal(SIGTERM)
        if (!awaitEmpty(gracePeriod)) {
            signal(SIGKILL)
            awaitEmpty(Duration.INFINITE)
        }
    }

    /** SIGSTOP the whole tree: a running transcription freezes its output (ADR 0004). */
    internal fun pause() {
        signal(SIGSTOP)
    }

    /** SIGCONT the whole tree; harmless when the group is not stopped. */
    internal fun resume() {
        signal(SIGCONT)
    }

    private suspend fun awaitEmpty(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (hasMembers()) delay(POLL_INTERVAL)
            true
        } ?: false

    private fun signal(signal: Int) {
        LibC.INSTANCE.kill(target, signal)
    }

    /** Signal 0 checks existence only: kill(2) succeeds while any process of the group exists. */
    private fun hasMembers(): Boolean = LibC.INSTANCE.kill(target, 0) == 0

    private companion object {
        const val SIGKILL = 9
        const val SIGTERM = 15
        const val SIGCONT = 18
        const val SIGSTOP = 19
    }
}
