package stt.db

import org.jetbrains.exposed.v1.core.Table
import stt.queue.TaskStatus

/** Exposed mapping of `tasks` (schema owned by V1__init.sql). */
internal object TasksTable : Table("tasks") {
    val id = long("id").autoIncrement()
    val originalFilename = text("original_filename")
    val contentType = text("content_type")
    val durationMs = long("duration_ms").nullable()
    val status = enumerationByName("status", 16, TaskStatus::class)
    val phase = enumerationByName("phase", 16, TaskPhase::class).nullable()
    val queuePosition = integer("queue_position").nullable()
    val language = text("language")
    val detectedLanguage = text("detected_language").nullable()
    val speakersMode =
        customEnumeration(
            "speakers_mode",
            fromDb = { SpeakersMode.fromDbValue(it as String) },
            toDb = { it.dbValue },
        )
    val speakersCount = integer("speakers_count").nullable()
    val maxSpeakers = integer("max_speakers")
    val model = text("model")
    val progress = double("progress")
    val diarizationStage = enumerationByName("diarization_stage", 16, DiarizationStage::class).nullable()
    val diarizationProgress = double("diarization_progress").nullable()
    val error = text("error").nullable()
    val createdAt = long("created_at")
    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
