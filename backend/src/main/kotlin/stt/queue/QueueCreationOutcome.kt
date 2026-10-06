package stt.queue

/** Outcome of building a queue through [TaskQueue.of]; expected rejections are typed, never exceptions. */
sealed interface QueueCreationOutcome {
    data class Accepted(
        val queue: TaskQueue,
    ) : QueueCreationOutcome

    data class Rejected(
        val reason: QueueCreationRejection,
    ) : QueueCreationOutcome
}
