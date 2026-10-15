package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of `original_audio_part` (V8__audio_in_database.sql): an original's bytes, one window per row. */
internal object OriginalAudioPartTable : Table("original_audio_part") {
    val originalId = long("original_id")
    val seq = integer("seq")
    val bytes = blob("bytes")

    override val primaryKey = PrimaryKey(originalId, seq)
}
