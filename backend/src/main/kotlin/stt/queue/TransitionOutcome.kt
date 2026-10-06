package stt.queue

/** Outcome of a task status transition; expected rejections are typed, never exceptions. */
sealed interface TransitionOutcome {
    /** The transition is in the table; the new status is the target the caller passed. */
    data object Accepted : TransitionOutcome

    /** The pair [from] → [to] is not in the spec's transition table (per-task rule). */
    data class Rejected(
        val from: TaskStatus,
        val to: TaskStatus,
    ) : TransitionOutcome
}
