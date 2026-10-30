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
    /** whisper's detected language (item 74); null until a run names one, then that of the first chunk naming any. */
    val detectedLanguage: String?,
    val speakersMode: SpeakersMode,
    val speakersCount: Int?,
    val maxSpeakers: Int,
    val model: String,
    val progress: Double,
    val diarizationStage: DiarizationStage?,
    val diarizationProgress: Double?,
    val error: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)
