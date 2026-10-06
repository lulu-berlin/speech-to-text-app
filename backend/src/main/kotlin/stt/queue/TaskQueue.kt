package stt.queue

/**
 * The queue aggregate: the ordered non-terminal tasks (id → status) — a PAUSED
 * task keeps its slot here and stays reorderable — and the queue paused flag.
 * Pure value: operations return a new instance or a typed rejection.
 */
data class TaskQueue(
    val entries: List<Pair<Long, TaskStatus>>,
    val paused: Boolean = false,
) {
    /** Pauses the whole queue. */
    fun pause(): TaskQueue = copy(paused = true)

    /** Resumes the whole queue. */
    fun resume(): TaskQueue = copy(paused = false)

    /**
     * Changes the status of task [id] to [to]: the per-task table decides legality, and
     * `→ RUNNING` is additionally refused while another task is `RUNNING`.
     */
    fun transition(
        id: Long,
        to: TaskStatus,
    ): QueueTransitionOutcome {
        val from = entries.first { (entryId, _) -> entryId == id }.second
        if (from.transition(to) is TransitionOutcome.Rejected) {
            return QueueTransitionOutcome.Rejected(QueueTransitionRejection.IllegalTransition(from, to))
        }
        if (to == TaskStatus.RUNNING && anotherTaskRunning(id)) {
            return QueueTransitionOutcome.Rejected(QueueTransitionRejection.AnotherTaskRunning)
        }
        return QueueTransitionOutcome.Accepted(
            copy(entries = entries.map { (entryId, status) -> entryId to if (entryId == id) to else status }),
        )
    }

    private fun anotherTaskRunning(id: Long): Boolean = entries.any { (entryId, status) -> entryId != id && status == TaskStatus.RUNNING }

    /** Applies [ids] as the new order of all non-terminal tasks; statuses travel with their ids. */
    fun reorder(ids: List<Long>): ReorderOutcome {
        val currentIds = entries.map { it.first }.toSet()
        return if (ids.toSet() == currentIds) {
            val byId = entries.toMap()
            ReorderOutcome.Accepted(copy(entries = ids.map { id -> id to byId.getValue(id) }))
        } else {
            ReorderOutcome.Rejected
        }
    }
}
