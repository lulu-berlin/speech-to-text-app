package stt.queue

/**
 * The queue aggregate: the ordered non-terminal tasks — a PAUSED
 * task keeps its slot here and stays reorderable — and the queue paused flag.
 * Pure value: operations return a new instance or a typed rejection.
 * Built only through [of]; the private constructor and `copy` keep every
 * instance valid (unique ids, no terminal entry, at most one `RUNNING` task).
 */
@ConsistentCopyVisibility
data class TaskQueue private constructor(
    val entries: List<QueueEntry>,
    val paused: Boolean,
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
        val from =
            entries.firstOrNull { it.id == id }?.status
                ?: return QueueTransitionOutcome.Rejected(QueueTransitionRejection.UnknownTask(id))
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

    /**
     * Applies [ids] as the new order of all non-terminal tasks, each named exactly once;
     * statuses travel with their ids. Otherwise rejects with the first of
     * duplicate > unknown > missing ids.
     */
    fun reorder(ids: List<Long>): ReorderOutcome {
        val rejection = reorderRejection(ids)
        if (rejection != null) return ReorderOutcome.Rejected(rejection)
        val byId = entries.associateBy { it.id }
        return ReorderOutcome.Accepted(copy(entries = ids.map { id -> byId.getValue(id) }))
    }

    private fun reorderRejection(ids: List<Long>): ReorderRejection? {
        val currentIds = entries.map { it.id }.toSet()
        val namedIds = ids.toSet()
        val duplicates = duplicateIds(ids)
        val unknown = namedIds - currentIds
        val missing = currentIds - namedIds
        return when {
            duplicates.isNotEmpty() -> ReorderRejection.DuplicateIds(duplicates)
            unknown.isNotEmpty() -> ReorderRejection.UnknownIds(unknown)
            missing.isNotEmpty() -> ReorderRejection.MissingIds(missing)
            else -> null
        }
    }

    companion object {
        /**
         * Builds a queue from [entries] (in queue order) and the [paused] flag, as item 11
         * restores them at boot. Rejects with the first of duplicate ids > terminal entries >
         * more than one `RUNNING` task.
         */
        fun of(
            entries: List<QueueEntry>,
            paused: Boolean = false,
        ): QueueCreationOutcome {
            val rejection = creationRejection(entries)
            return if (rejection != null) {
                QueueCreationOutcome.Rejected(rejection)
            } else {
                QueueCreationOutcome.Accepted(TaskQueue(entries, paused))
            }
        }

        private fun creationRejection(entries: List<QueueEntry>): QueueCreationRejection? {
            val duplicates = duplicateIds(entries.map { it.id })
            val terminal = entries.filter { it.status.isTerminal }.map { it.id }.toSet()
            val running = entries.filter { it.status == TaskStatus.RUNNING }.map { it.id }.toSet()
            return when {
                duplicates.isNotEmpty() -> QueueCreationRejection.DuplicateIds(duplicates)
                terminal.isNotEmpty() -> QueueCreationRejection.TerminalEntries(terminal)
                running.size > 1 -> QueueCreationRejection.MultipleRunning(running)
                else -> null
            }
        }
    }
}

/** The ids that occur more than once in [ids]. */
private fun duplicateIds(ids: List<Long>): Set<Long> =
    ids
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
