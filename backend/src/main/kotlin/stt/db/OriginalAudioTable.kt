package stt.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed mapping of `original_audio` (schema owned by V8__audio_in_database.sql; item 44): one upload's original,
 * staged with no task while it is received and attached to its task when the task is created.
 */
internal object OriginalAudioTable : Table("original_audio") {
    val id = long("id").autoIncrement()
    val taskId = long("task_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
