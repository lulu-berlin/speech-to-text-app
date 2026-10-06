package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `audio` (schema owned by V1__init.sql). */
internal object AudioTable : Table("audio") {
    val taskId = long("task_id")
    val sizeBytes = long("size_bytes")
    val sha256 = text("sha256")

    override val primaryKey = PrimaryKey(taskId)
}
