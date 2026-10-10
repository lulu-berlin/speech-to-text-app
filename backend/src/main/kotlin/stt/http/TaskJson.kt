package stt.http

import kotlinx.serialization.Serializable
import stt.db.TaskDetails

/** The Task of item 12's contract (`docs/api/task.*.json`), as every route and event sends it. */
@Serializable
data class TaskJson(
    val id: Long,
    val filename: String,
    val status: String,
    val phase: String?,
    val language: String,
    val speakersMode: String,
    val speakersCount: Int?,
    val maxSpeakers: Int,
    val durationMs: Long?,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val transcriptionProgress: Double,
    val diarizationStage: String?,
    val diarizationProgress: Double?,
    val error: String?,
    val transcribedUntilMs: Long?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)

/** The response of `GET /api/tasks` (item 12's contract, `docs/api/task-list.json`). */
@Serializable
data class TaskListJson(
    val tasks: List<TaskJson>,
)

fun TaskDetails.toJson(): TaskJson =
    TaskJson(
        id = id,
        filename = task.originalFilename,
        status = task.status.name,
        phase = task.phase?.name,
        language = task.language,
        speakersMode = task.speakersMode.dbValue,
        speakersCount = task.speakersCount,
        maxSpeakers = task.maxSpeakers,
        durationMs = task.durationMs,
        sizeBytes = sizeBytes,
        hasAudio = hasAudio,
        transcriptionProgress = task.progress,
        diarizationStage = task.diarizationStage?.name,
        diarizationProgress = task.diarizationProgress,
        error = task.error,
        transcribedUntilMs = transcribedUntilMs,
        createdAt = task.createdAt,
        startedAt = task.startedAt,
        finishedAt = task.finishedAt,
    )
