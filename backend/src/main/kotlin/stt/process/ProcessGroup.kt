package stt.process

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The process group of one run: the pid of a `setsid`-spawned child is its group id, and
 * kill(2) with the negated id reaches every process in the tree (ADR 0020). Linux signal numbers.
 *
 * start() returns before setsid has created the group, so every signal goes to the direct child
 * first, then to the group: until setsid() runs, the child is the whole run, and a child that is
 * stopped or killed can no longer create the group or fork into it.
 */
internal class ProcessGroup(
    private val id: Long,
    /** Whether the direct child is still unreaped; its pid is signalled only then, never a reused one. */
    private val childAlive: () -> Boolean,
    /** kill(2); a seam so tests can simulate a group that outlives SIGKILL (ADR 0020). */
    private val kill: (pid: Int, signal: Int) -> Int = LibC.INSTANCE::kill,
) {
    /** kill(2) addresses a whole group through the negated group id. */
    private val target = -id.toInt()

    /**
     * SIGCONT first so a stopped tree can act on SIGTERM; SIGKILL if it outlives the grace period.
     * The wait after SIGKILL is bounded by the grace period too: members that cannot die or be reaped
     * (zombies without a reaping init, uninterruptible sleep) must not hang the caller (ADR 0020).
     */
    suspend fun terminate(gracePeriod: Duration) {
        signal(SIGCONT)
        signal(SIGTERM)
        if (awaitEmpty(gracePeriod)) return
        signal(SIGKILL)
        if (!awaitEmpty(gracePeriod)) {
            logger.warn(
                "Process group {} still has members {} after SIGKILL (unreaped zombies or uninterruptible sleep); not waiting longer",
                id,
                gracePeriod,
            )
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

    /** The child first: once it is stopped or dead it cannot add processes the group signal would miss. */
    private fun signal(signal: Int) {
        if (childAlive()) kill(id.toInt(), signal)
        kill(target, signal)
    }

    /** The live child is a member even before setsid() creates the group; signal 0 checks the group's existence. */
    private fun hasMembers(): Boolean = childAlive() || kill(target, 0) == 0

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ProcessGroup::class.java)

        const val SIGKILL = 9
        const val SIGTERM = 15
        const val SIGCONT = 18
        const val SIGSTOP = 19
    }
}
