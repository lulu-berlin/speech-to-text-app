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

    /** `COMPLETED`, `FAILED` and `CANCELED` are final; a task in one of them has left the queue. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED

    /**
     * Applies the transition `this` → [to] per the spec's per-task table
     * (roadmap item 04, as amended 2026-10-06). The whole-queue guard on
     * `QUEUED → RUNNING` (at most one running task) lives on the queue aggregate,
     * not here.
     */
    fun transition(to: TaskStatus): TransitionOutcome {
        val allowed = allowedTargets[this].orEmpty()
        return if (to in allowed) TransitionOutcome.Accepted(to) else TransitionOutcome.Rejected(this, to)
    }

    companion object {
        /** Spec's allowed targets per source; terminal sources have none. */
        private val allowedTargets: Map<TaskStatus, Set<TaskStatus>> =
            mapOf(
                QUEUED to setOf(RUNNING, PAUSED, CANCELED),
                RUNNING to setOf(PAUSED, COMPLETED, FAILED, CANCELED),
                PAUSED to setOf(QUEUED, CANCELED),
            )
    }
}
