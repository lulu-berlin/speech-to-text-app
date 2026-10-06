package stt.queue

/** Lifecycle status of a task in the queue; persisted by name into `tasks.status`. */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
}
