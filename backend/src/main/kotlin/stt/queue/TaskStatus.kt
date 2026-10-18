package stt.queue

/** Lifecycle status of a task in the queue; persisted by name into `tasks.status`. */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
    ;

    /**
     * A status whose tasks have left the queue (`COMPLETED`, `FAILED`, `CANCELED`). It is not
     * "final": only `COMPLETED` is; `FAILED` and `CANCELED` re-enter the queue by retry
     * (roadmap item 11, amending item 04), so it is named explicitly rather than derived
     * from "has no allowed targets".
     */
    val isTerminal: Boolean
        get() = this in terminalStatuses

    /**
     * Whether the transition `this` → [to] is in the spec's per-task table
     * (roadmap item 04, as amended by item 11 for retry and by item 42 for a task whose
     * process is gone or whose start itself fails, ADR 0057/0058). The whole-queue guard on
     * `QUEUED → RUNNING` (at most one running task) lives on the queue aggregate, not here.
     */
    fun canTransitionTo(to: TaskStatus): Boolean = to in allowedTargets[this].orEmpty()

    companion object {
        /** Statuses that leave the queue; `FAILED` and `CANCELED` re-enter it only by retry. */
        private val terminalStatuses = setOf(COMPLETED, FAILED, CANCELED)

        /** Spec's allowed targets per source; a source absent here has no transition at all. */
        private val allowedTargets: Map<TaskStatus, Set<TaskStatus>> =
            mapOf(
                QUEUED to setOf(RUNNING, PAUSED, FAILED, CANCELED),
                RUNNING to setOf(QUEUED, PAUSED, COMPLETED, FAILED, CANCELED),
                PAUSED to setOf(QUEUED, CANCELED),
                FAILED to setOf(QUEUED),
                CANCELED to setOf(QUEUED),
            )
    }
}
