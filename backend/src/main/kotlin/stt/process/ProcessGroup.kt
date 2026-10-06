package stt.process

/**
 * The process group of one run: the pid of a `setsid`-spawned child is its group id, and
 * kill(2) with the negated id reaches every process in the tree (ADR 0020). Linux signal numbers.
 */
internal class ProcessGroup(
    private val id: Long,
) {
    fun signal(signal: Int) {
        LibC.INSTANCE.kill(-id.toInt(), signal)
    }

    /** Signal 0 checks existence only: kill(2) succeeds while any process of the group exists. */
    fun hasMembers(): Boolean = LibC.INSTANCE.kill(-id.toInt(), 0) == 0

    companion object {
        const val SIGKILL = 9
        const val SIGTERM = 15
        const val SIGCONT = 18
    }
}
