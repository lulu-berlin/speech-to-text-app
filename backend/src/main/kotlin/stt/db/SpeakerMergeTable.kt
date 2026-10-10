package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `speaker_merge` (schema owned by V5__speaker_merge.sql; item 22): one row per merged speaker. */
internal object SpeakerMergeTable : Table("speaker_merge") {
    val taskId = long("task_id")
    val mergedId = integer("merged_id")
    val intoId = integer("into_id")

    override val primaryKey = PrimaryKey(taskId, mergedId)
}
