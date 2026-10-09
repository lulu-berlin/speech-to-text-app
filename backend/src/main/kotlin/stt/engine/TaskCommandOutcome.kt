package stt.engine

import stt.queue.TaskStatus

/**
 * What a task command (pause, resume, cancel, retry) did. The engine reports the reason instead of
 * throwing, because a refusal is an expected outcome; item 12's routes map [Unknown] onto
 * `404 not-found` and [Illegal] onto `409 illegal-transition`.
 */
sealed interface TaskCommandOutcome {
    /** The command was carried out. */
    data object Accepted : TaskCommandOutcome

    /** No task with that id exists. */
    data object Unknown : TaskCommandOutcome

    /** Item 04's per-task table does not allow [from] → [to]. */
    data class Illegal(
        val from: TaskStatus,
        val to: TaskStatus,
    ) : TaskCommandOutcome
}
