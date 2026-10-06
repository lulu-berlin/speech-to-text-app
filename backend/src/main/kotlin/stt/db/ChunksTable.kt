package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `chunks` (schema owned by V1__init.sql). */
internal object ChunksTable : Table("chunks") {
    val taskId = long("task_id")
    val idx = integer("idx")
    val startMs = long("start_ms")
    val endMs = long("end_ms")
    val status = enumerationByName("status", 16, ChunkStatus::class)

    override val primaryKey = PrimaryKey(taskId, idx)
}
