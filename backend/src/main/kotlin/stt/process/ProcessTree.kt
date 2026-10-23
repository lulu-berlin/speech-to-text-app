package stt.process

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The process tree of one run. On Linux the direct child is a `setsid`-spawned group leader, and
 * kill(2) with the negated id reaches every process in the group; on Darwin (no setsid) the run is
 * the direct child alone (ADR 0020). Signal numbers come from [ProcessPlatform].
 *
 * On Linux start() returns before setsid has created the group, so every signal goes to the direct
 * child first, then to the group: until setsid() runs, the child is the whole run, and a child that
 * is stopped or killed can no longer create the group or fork into it.
 */
internal class ProcessTree(
    private val id: Long,
    /** Whether the direct child is still unreaped; its pid is signalled only then, never a reused one. */
    private val childAlive: () -> Boolean,
    /** The host platform's signals and process-group support; production uses the JVM's `os.name`. */
    private val platform: ProcessPlatform = ProcessPlatform.current(),
    /** kill(2); a seam so tests can simulate a group that outlives SIGKILL (ADR 0020). */
    private val kill: (pid: Int, signal: Int) -> Int = LibC.INSTANCE::kill,
) {
    init {
        // A platform without signals never builds a tree (Windows destroys its direct child instead), so
        // this is a programming error rather than a no-op terminate().
        require(platform.signals) { "a process tree needs signals; this platform has none" }
    }

    /** kill(2) addresses a whole group through the negated group id (Linux only). */
    private val target = -id.toInt()

    /**
     * SIGCONT first so a stopped tree can act on SIGTERM; SIGKILL if it outlives the grace period.
     * The wait after SIGKILL is bounded by the grace period too: members that cannot die or be reaped
     * (zombies without a reaping init, uninterruptible sleep) must not hang the caller (ADR 0020).
     */
    suspend fun terminate(gracePeriod: Duration) {
        signal(platform.cont)
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
        signal(platform.stop)
    }

    /** SIGCONT the whole tree; harmless when the group is not stopped. */
    internal fun resume() {
        signal(platform.cont)
    }

    private suspend fun awaitEmpty(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (hasMembers()) delay(POLL_INTERVAL)
            true
        } ?: false

    /** The child first (Linux: then the group): once it is stopped or dead it cannot add processes. */
    private fun signal(signal: Int?) {
        if (signal == null) return
        if (childAlive()) kill(id.toInt(), signal)
        if (platform.processGroups) kill(target, signal)
    }

    /** On Linux the live child is a member before setsid() creates the group; signal 0 checks the group. */
    private fun hasMembers(): Boolean = childAlive() || (platform.processGroups && kill(target, 0) == 0)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ProcessTree::class.java)

        const val SIGKILL = 9
        const val SIGTERM = 15
    }
}
