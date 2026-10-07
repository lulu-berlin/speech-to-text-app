package stt.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** An upload's audio metadata (ADR 0024: the upload's bytes are not stored in the database). */
class AudioRepository(
    private val db: Database,
) {
    suspend fun metadata(taskId: Long): AudioMetadata? =
        db.readTransaction {
            AudioTable
                .selectAll()
                .where { AudioTable.taskId eq taskId }
                .singleOrNull()
                ?.let { row -> AudioMetadata(row[AudioTable.sizeBytes], row[AudioTable.sha256]) }
        }
}

/** Inside the current transaction: inserts [taskId]'s `audio` row; a duplicate sha256 is rejected here (UNIQUE). */
internal fun JdbcTransaction.insertAudioRow(
    taskId: Long,
    audio: SpooledUpload,
) {
    AudioTable.insert {
        it[AudioTable.taskId] = taskId
        it[sizeBytes] = audio.sizeBytes
        it[sha256] = audio.sha256
    }
}
