package stt.engine

import stt.db.DiarizationStage
import stt.db.StoredTask

/**
 * A state change the engine publishes for live updates (ADR 0007, roadmap item 11); the SSE endpoint
 * (item 12) serializes these into its `task-updated` and `task-progress` events. Every event is emitted
 * only after the change it reports has been persisted, so a client that reconnects can trust the snapshot.
 */
sealed interface Event {
    /** The task's status, phase or error changed; carrying the whole task makes a repeated event harmless. */
    data class TaskUpdated(
        val task: StoredTask,
    ) : Event

    /** The transcription and diarization progress of a task (item 12's `task-progress` event). */
    data class TaskProgress(
        val id: Long,
        val transcriptionProgress: Double,
        val diarizationStage: DiarizationStage?,
        val diarizationProgress: Double?,
    ) : Event

    /** The task's row, and everything cascading with it, was deleted; item 12's `task-deleted` event. */
    data class TaskDeleted(
        val id: Long,
    ) : Event

    /** The queue as it is now; item 12's `queue-updated` event. */
    data class QueueUpdated(
        val paused: Boolean,
        val taskIds: List<Long>,
    ) : Event
}
