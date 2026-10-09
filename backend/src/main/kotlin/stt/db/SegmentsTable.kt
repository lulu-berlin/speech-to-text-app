package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `segments` (schema owned by V1__init.sql, rebuilt with `speaker_id` by V3__speakers.sql; ADR 0028). */
internal object SegmentsTable : Table("segments") {
    val id = long("id").autoIncrement()
    val taskId = long("task_id")
    val chunkIdx = integer("chunk_idx")
    val seq = integer("seq")
    val startMs = long("start_ms")
    val endMs = long("end_ms")
    val text = text("text")
    val speakerId = integer("speaker_id").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(taskId to SpeakersTable.taskId, speakerId to SpeakersTable.speakerId)
    }
}
