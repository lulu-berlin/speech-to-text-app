package stt.queue

/** Why the queue refused a status change. */
sealed interface QueueTransitionRejection {
    /** The pair [from] → [to] is not in the per-task transition table. */
    data class IllegalTransition(
        val from: TaskStatus,
        val to: TaskStatus,
    ) : QueueTransitionRejection

    /** `QUEUED → RUNNING` while another task is already `RUNNING` (at most one running task). */
    data object AnotherTaskRunning : QueueTransitionRejection
}
