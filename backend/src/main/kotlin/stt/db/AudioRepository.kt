package stt.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Audio payload as fixed-size pages (ADR 0018): at most one page is in memory at a time. */
class AudioRepository(
    private val db: Database,
) {
    /**
     * Streams [source] into `audio_parts` pages and records its size and sha256 in `audio`,
     * all in one transaction. The source is read on Dispatchers.IO and not closed.
     */
    suspend fun write(
        taskId: Long,
        source: InputStream,
    ) {
        db.transaction {
            val digest = MessageDigest.getInstance("SHA-256")
            val page = ByteArray(PAGE_SIZE)
            var sizeBytes = 0L
            var partNo = 0
            while (true) {
                val n = source.readNBytes(page, 0, PAGE_SIZE)
                if (n == 0) break
                digest.update(page, 0, n)
                // The insert executes before the next read, so a full page buffer is reused safely.
                val bytes = if (n == PAGE_SIZE) page else page.copyOf(n)
                AudioPartsTable.insert {
                    it[AudioPartsTable.taskId] = taskId
                    it[AudioPartsTable.partNo] = partNo
                    it[part] = ExposedBlob(bytes)
                }
                sizeBytes += n
                partNo++
            }
            AudioTable.insert {
                it[AudioTable.taskId] = taskId
                it[AudioTable.sizeBytes] = sizeBytes
                it[sha256] = HexFormat.of().formatHex(digest.digest())
            }
        }
    }

    suspend fun metadata(taskId: Long): AudioMetadata? =
        db.transaction {
            AudioTable
                .selectAll()
                .where { AudioTable.taskId eq taskId }
                .singleOrNull()
                ?.let { row -> AudioMetadata(row[AudioTable.sizeBytes], row[AudioTable.sha256]) }
        }

    /** Writes the task's payload to [target] page by page, in `part_no` order; [target] is not closed. */
    suspend fun copyTo(
        taskId: Long,
        target: OutputStream,
    ) {
        db.transaction {
            AudioPartsTable
                .select(AudioPartsTable.part)
                .where { AudioPartsTable.taskId eq taskId }
                .orderBy(AudioPartsTable.partNo)
                .forEach { row -> target.write(row[AudioPartsTable.part].bytes) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 1_048_576
    }
}
