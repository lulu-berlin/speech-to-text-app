package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `chunk_audio` (schema owned by V2__chunk_audio.sql; ADR 0024): one FLAC per chunk. */
internal object ChunkAudioTable : Table("chunk_audio") {
    val taskId = long("task_id")
    val idx = integer("idx")
    val flac = blob("flac")

    override val primaryKey = PrimaryKey(taskId, idx)
}
