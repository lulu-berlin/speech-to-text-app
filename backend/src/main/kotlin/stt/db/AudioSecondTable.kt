package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `audio_second` (V9__canonical_audio.sql): one second of a task's canonical FLAC, four 250 ms frames per row. */
internal object AudioSecondTable : Table("audio_second") {
    val taskId = long("task_id")
    val second = integer("second")
    val frames = integer("frames")
    val bytes = blob("bytes")

    override val primaryKey = PrimaryKey(taskId, second)
}
