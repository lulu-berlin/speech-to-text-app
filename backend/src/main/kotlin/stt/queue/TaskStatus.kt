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
     * A status with no allowed targets is final (`COMPLETED`, `FAILED`, `CANCELED`);
     * a task in one of them has left the queue.
     */
    val isTerminal: Boolean
        get() = allowedTargets[this].isNullOrEmpty()

    /**
     * Whether the transition `this` → [to] is in the spec's per-task table
     * (roadmap item 04, as amended 2026-10-06). The whole-queue guard on
     * `QUEUED → RUNNING` (at most one running task) lives on the queue aggregate,
     * not here.
     */
    fun canTransitionTo(to: TaskStatus): Boolean = to in allowedTargets[this].orEmpty()

    companion object {
        /** Spec's allowed targets per source; a source without targets is terminal. */
        private val allowedTargets: Map<TaskStatus, Set<TaskStatus>> =
            mapOf(
                QUEUED to setOf(RUNNING, PAUSED, CANCELED),
                RUNNING to setOf(PAUSED, COMPLETED, FAILED, CANCELED),
                PAUSED to setOf(QUEUED, CANCELED),
            )
    }
}
