package stt.queue

/**
 * The queue aggregate: the ordered non-terminal tasks — a PAUSED
 * task keeps its slot here and stays reorderable — and the queue paused flag.
 * Pure value: operations return a new instance or a typed rejection.
 */
data class TaskQueue(
    val entries: List<QueueEntry>,
    val paused: Boolean = false,
) {
    /** Pauses the whole queue. */
    fun pause(): TaskQueue = copy(paused = true)

    /** Resumes the whole queue. */
    fun resume(): TaskQueue = copy(paused = false)

    /**
     * Changes the status of task [id] to [to]: the per-task table decides legality, and
     * `→ RUNNING` is additionally refused while another task is `RUNNING`. A task that
     * reaches a terminal status leaves the queue (it stays in the database for history).
     */
    fun transition(
        id: Long,
        to: TaskStatus,
    ): QueueTransitionOutcome {
        val from = entries.first { it.id == id }.status
        if (from.transition(to) is TransitionOutcome.Rejected) {
            return QueueTransitionOutcome.Rejected(QueueTransitionRejection.IllegalTransition(from, to))
        }
        if (to == TaskStatus.RUNNING && anotherTaskRunning(id)) {
            return QueueTransitionOutcome.Rejected(QueueTransitionRejection.AnotherTaskRunning)
        }
        val updated =
            if (to.isTerminal) {
                entries.filterNot { it.id == id }
            } else {
                entries.map { if (it.id == id) it.copy(status = to) else it }
            }
        return QueueTransitionOutcome.Accepted(copy(entries = updated))
    }

    private fun anotherTaskRunning(id: Long): Boolean = entries.any { it.id != id && it.status == TaskStatus.RUNNING }

    /** Applies [ids] as the new order of all non-terminal tasks; statuses travel with their ids. */
    fun reorder(ids: List<Long>): ReorderOutcome {
        val currentIds = entries.map { it.id }.toSet()
        return if (ids.toSet() == currentIds) {
            val byId = entries.associateBy { it.id }
            ReorderOutcome.Accepted(copy(entries = ids.map { id -> byId.getValue(id) }))
        } else {
            ReorderOutcome.Rejected
        }
    }
}
