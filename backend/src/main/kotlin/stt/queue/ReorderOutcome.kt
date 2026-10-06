package stt.queue

/** Outcome of a queue reorder; expected rejections are typed, never exceptions. */
sealed interface ReorderOutcome {
    data class Accepted(
        val queue: TaskQueue,
    ) : ReorderOutcome

    /** [ids] is not exactly the set of current non-terminal ids (missing, unknown, terminal, or duplicate). */
    data object Rejected : ReorderOutcome
}
