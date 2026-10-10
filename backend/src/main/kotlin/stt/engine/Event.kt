package stt.engine

import stt.db.DiarizationStage
import stt.db.Speaker
import stt.db.StoredTask

/**
 * The engine's event-flow buffer and the SSE stream's numbered bridge buffer, as one constant (item 28):
 * both [ProcessingEngine] and [stt.http.NumberedEventStream] drop the oldest events for a subscriber
 * that lags beyond this many emissions.
 */
const val EVENT_BUFFER = 64

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

    /** A speaker of the task was renamed or had its name reset; item 22's `speakers-renamed` event. */
    data class SpeakersRenamed(
        val id: Long,
        val speakers: List<Speaker>,
    ) : Event

    /** The task's merges changed (a merge, or a reset of all merges); item 22's `speakers-merged` event. */
    data class SpeakersMerged(
        val id: Long,
        val speakers: List<Speaker>,
    ) : Event
}
