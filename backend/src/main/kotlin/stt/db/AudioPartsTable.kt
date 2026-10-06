package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `audio_parts` (schema owned by V1__init.sql; ADR 0018). */
internal object AudioPartsTable : Table("audio_parts") {
    val taskId = long("task_id")
    val partNo = integer("part_no")
    val part = blob("part")

    override val primaryKey = PrimaryKey(taskId, partNo)
}
