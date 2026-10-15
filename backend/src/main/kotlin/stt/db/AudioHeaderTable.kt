package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `audio_header` (V9__canonical_audio.sql): a task's canonical FLAC header, `fLaC` + metadata. */
internal object AudioHeaderTable : Table("audio_header") {
    val taskId = long("task_id")
    val header = blob("header")

    override val primaryKey = PrimaryKey(taskId)
}
