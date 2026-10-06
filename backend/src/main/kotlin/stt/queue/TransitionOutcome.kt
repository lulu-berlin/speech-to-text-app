package stt.queue

/** Outcome of a task status transition; expected rejections are typed, never exceptions. */
sealed interface TransitionOutcome {
    data class Accepted(
        val status: TaskStatus,
    ) : TransitionOutcome

    /** The pair [from] → [to] is not in the spec's transition table (per-task rule). */
    data class Rejected(
        val from: TaskStatus,
        val to: TaskStatus,
    ) : TransitionOutcome
}
