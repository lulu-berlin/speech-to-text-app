package stt.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Reads a task's canonical FLAC frames, one second per row (V9); [ChunkRepository.storeChunked] writes them. */
class AudioSecondRepository(
    private val db: Database,
) {
    /** The task's canonical rows in `second` order. */
    suspend fun read(taskId: Long): List<CanonicalAudio> =
        db.readTransaction {
            AudioSecondTable
                .selectAll()
                .where { AudioSecondTable.taskId eq taskId }
                .orderBy(AudioSecondTable.second)
                .map { row ->
                    CanonicalAudio(
                        second = row[AudioSecondTable.second],
                        frames = row[AudioSecondTable.frames],
                        bytes = row[AudioSecondTable.bytes].bytes,
                    )
                }
        }

    /** How many one-second rows the task has stored: a count, without reading the frames. */
    suspend fun count(taskId: Long): Long =
        db.readTransaction { AudioSecondTable.selectAll().where { AudioSecondTable.taskId eq taskId }.count() }
}

/** Replaces the task's canonical rows with [rows], inserted as [rows] yields them, inside the caller's transaction. */
internal fun JdbcTransaction.replaceCanonicalRows(
    taskId: Long,
    rows: Iterable<CanonicalAudio>,
) {
    AudioSecondTable.deleteWhere { AudioSecondTable.taskId eq taskId }
    rows.forEach { row ->
        AudioSecondTable.insert {
            it[AudioSecondTable.taskId] = taskId
            it[AudioSecondTable.second] = row.second
            it[AudioSecondTable.frames] = row.frames
            it[bytes] = ExposedBlob(row.bytes)
        }
    }
}
