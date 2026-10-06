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

    /** Applies [ids] as the new order of all non-terminal tasks; statuses travel with their ids. */
    fun reorder(ids: List<Long>): ReorderOutcome {
        val currentIds = entries.map { it.first }.toSet()
        return if (ids.toSet() == currentIds) {
            val byId = entries.toMap()
            ReorderOutcome.Accepted(TaskQueue(ids.map { id -> id to byId.getValue(id) }))
        } else {
            ReorderOutcome.Rejected
        }
    }
}
