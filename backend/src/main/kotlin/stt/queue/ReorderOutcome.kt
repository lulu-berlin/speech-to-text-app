package stt.queue

/** Outcome of a queue reorder; expected rejections are typed, never exceptions. */
sealed interface ReorderOutcome {
    data class Accepted(
        val queue: TaskQueue,
    ) : ReorderOutcome

    data class Rejected(
        val reason: ReorderRejection,
    ) : ReorderOutcome
}
