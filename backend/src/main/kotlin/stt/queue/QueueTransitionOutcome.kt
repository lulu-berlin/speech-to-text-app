package stt.queue

/** Outcome of a status change applied through the queue; expected rejections are typed, never exceptions. */
sealed interface QueueTransitionOutcome {
    data class Accepted(
        val queue: TaskQueue,
    ) : QueueTransitionOutcome

    data class Rejected(
        val reason: QueueTransitionRejection,
    ) : QueueTransitionOutcome
}
