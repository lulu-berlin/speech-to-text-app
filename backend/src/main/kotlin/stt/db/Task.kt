package stt.db

import stt.queue.TaskStatus

/** A transcription task as stored in `tasks`; timestamps are epoch millis UTC. */
data class Task(
    val originalFilename: String,
    val contentType: String,
    val durationMs: Long?,
    val status: TaskStatus,
    val phase: TaskPhase?,
    val queuePosition: Int?,
    val language: String,
    val speakersMode: SpeakersMode,
    val speakersCount: Int?,
    val maxSpeakers: Int,
    val model: String,
    val progress: Double,
    val error: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)
