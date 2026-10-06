package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `segments` (schema owned by V1__init.sql). */
internal object SegmentsTable : Table("segments") {
    val id = long("id").autoIncrement()
    val taskId = long("task_id")
    val chunkIdx = integer("chunk_idx")
    val seq = integer("seq")
    val startMs = long("start_ms")
    val endMs = long("end_ms")
    val text = text("text")
    val speaker = text("speaker").nullable()

    override val primaryKey = PrimaryKey(id)
}
