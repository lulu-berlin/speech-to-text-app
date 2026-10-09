package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `speakers` (schema owned by V3__speakers.sql; ADR 0028): one row per speaker of a task. */
internal object SpeakersTable : Table("speakers") {
    val taskId = long("task_id")
    val speakerId = integer("speaker_id")
    val speaker = text("speaker")

    override val primaryKey = PrimaryKey(taskId, speakerId)
}
