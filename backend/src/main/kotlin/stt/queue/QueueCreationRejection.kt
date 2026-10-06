package stt.queue

/**
 * Why the factory refused to build a queue from a set of entries. When several
 * apply, the first of duplicate > terminal > multiple running is reported.
 */
sealed interface QueueCreationRejection {
    /** [ids] appear in more than one entry. */
    data class DuplicateIds(
        val ids: Set<Long>,
    ) : QueueCreationRejection

    /** [ids] have a terminal status; a finished task has left the queue. */
    data class TerminalEntries(
        val ids: Set<Long>,
    ) : QueueCreationRejection

    /** [ids] are all `RUNNING`; at most one task may run at a time. */
    data class MultipleRunning(
        val ids: Set<Long>,
    ) : QueueCreationRejection
}
