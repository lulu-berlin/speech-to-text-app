package stt.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/** Reads a task's canonical FLAC header (V9); [ChunkRepository.storeChunked] writes it. */
class AudioHeaderRepository(
    private val db: Database,
) {
    /** The task's stored header bytes, or null when the task has no canonical audio. */
    suspend fun read(taskId: Long): ByteArray? =
        db.readTransaction {
            AudioHeaderTable
                .select(AudioHeaderTable.header)
                .where { AudioHeaderTable.taskId eq taskId }
                .singleOrNull()
                ?.get(AudioHeaderTable.header)
                ?.bytes
        }
}

/** Inserts [header] as the task's canonical FLAC header, inside the caller's transaction. */
internal fun JdbcTransaction.insertHeader(
    taskId: Long,
    header: ByteArray,
) {
    AudioHeaderTable.insert {
        it[AudioHeaderTable.taskId] = taskId
        it[AudioHeaderTable.header] = ExposedBlob(header)
    }
}
