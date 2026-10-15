package stt.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.OutputStream

/** An upload's audio metadata and its original bytes, stored in the database as they arrived (item 44). */
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

    /** The task that already uploaded these bytes, if any; the unique `sha256` makes this unambiguous (item 12). */
    suspend fun taskIdOfSha256(sha256: String): Long? =
        db.readTransaction {
            AudioTable
                .selectAll()
                .where { AudioTable.sha256 eq sha256 }
                .singleOrNull()
                ?.get(AudioTable.taskId)
        }

    /**
     * Writes [taskId]'s original upload to [target] in upload order, one stored window at a time, inside one read
     * transaction on its own read-only connection; [target] is not closed. Throws [NoSuchElementException] when
     * the task has no stored original.
     */
    suspend fun copyOriginalTo(
        taskId: Long,
        target: OutputStream,
    ) {
        val found =
            db.readTransaction {
                val originalId =
                    OriginalAudioTable
                        .select(OriginalAudioTable.id)
                        .where { OriginalAudioTable.taskId eq taskId }
                        .singleOrNull()
                        ?.get(OriginalAudioTable.id)
                        ?: return@readTransaction false
                OriginalAudioPartTable
                    .select(OriginalAudioPartTable.bytes)
                    .where { OriginalAudioPartTable.originalId eq originalId }
                    .orderBy(OriginalAudioPartTable.seq)
                    .forEach { row -> target.write(row[OriginalAudioPartTable.bytes].bytes) }
                true
            }
        if (!found) throw NoSuchElementException("no stored original for task $taskId")
    }

    /** Removes [taskId]'s stored original and its parts — called once the canonical clip is stored (item 44). */
    suspend fun deleteOriginal(taskId: Long) {
        db.writeTransaction {
            OriginalAudioTable.deleteWhere { OriginalAudioTable.taskId eq taskId }
        }
    }
}

/**
 * Inside the current transaction: inserts [taskId]'s `audio` row and attaches the staged original [audio] to the
 * task; a duplicate sha256 is rejected here (UNIQUE). Fails when the staged original is gone or already claimed.
 */
internal fun JdbcTransaction.insertAudioRow(
    taskId: Long,
    audio: StagedUpload,
) {
    AudioTable.insert {
        it[AudioTable.taskId] = taskId
        it[sizeBytes] = audio.sizeBytes
        it[sha256] = audio.sha256
    }
    val attached =
        OriginalAudioTable.update({ (OriginalAudioTable.id eq audio.originalId) and OriginalAudioTable.taskId.isNull() }) {
            it[OriginalAudioTable.taskId] = taskId
        }
    check(attached == 1) { "the staged original ${audio.originalId} of task $taskId is gone or already claimed" }
}
