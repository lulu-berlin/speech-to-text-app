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
     * Applies the transition `this` → [to] per the spec's per-task table
     * (roadmap item 04, as amended 2026-10-06). The whole-queue guard on
     * `QUEUED → RUNNING` (at most one running task) lives on the queue aggregate,
     * not here.
     */
    fun transition(to: TaskStatus): TransitionOutcome {
        val allowed =
            when (this) {
                QUEUED -> setOf(RUNNING, PAUSED, CANCELED)
                RUNNING -> setOf(PAUSED, COMPLETED, FAILED, CANCELED)
                PAUSED -> setOf(QUEUED, CANCELED)
                COMPLETED, FAILED, CANCELED -> emptySet()
            }
        return if (to in allowed) TransitionOutcome.Accepted(to) else TransitionOutcome.Rejected(this, to)
    }
}
