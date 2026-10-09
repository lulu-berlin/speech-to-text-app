package stt.engine

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
}
