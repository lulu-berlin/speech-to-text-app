package stt.queue

/** One pending task in the queue: its id and its current status. */
data class QueueEntry(
    val id: Long,
    val status: TaskStatus,
)
