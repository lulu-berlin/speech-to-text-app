package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val PAGE_SIZE = 1_048_576

/** Audio payload as fixed-size pages (ADR 0018): at most one page is in memory at a time. */
class AudioRepository(
    private val db: Database,
) {
    /**
     * Spools [source] to a temp file (size and sha256 computed on the way), then writes its
     * `audio` row and `audio_parts` pages in one short transaction. [source] is read on
     * Dispatchers.IO and not closed.
     */
    suspend fun write(
        taskId: Long,
        source: InputStream,
    ) {
        db.withSpooledAudio(source) { audio -> db.writeTransaction { writeAudioRows(taskId, audio) } }
    }

    suspend fun metadata(taskId: Long): AudioMetadata? =
        db.readTransaction {
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
        db.readTransaction {
            AudioPartsTable
                .select(AudioPartsTable.part)
                .where { AudioPartsTable.taskId eq taskId }
                .orderBy(AudioPartsTable.partNo)
                .forEach { row -> target.write(row[AudioPartsTable.part].bytes) }
        }
    }
}

/** An upload copied to a temp file under `$STT_DATA_DIR/tmp`, with its size and sha256. */
internal class SpooledAudio(
    val file: Path,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Copies [source] to a temp file under [Database.tmpDir] in page-sized windows, computing its size
 * and sha256 on the way, runs [block] with it, and deletes the file afterwards unless [block] moved
 * it away, whether [block] or the copy succeeded or failed. No database lock is held while the source is read.
 */
internal suspend fun <T> Database.withSpooledAudio(
    source: InputStream,
    block: suspend (SpooledAudio) -> T,
): T {
    val file = withContext(Dispatchers.IO) { Files.createTempFile(tmpDir, "upload-", ".part") }
    try {
        val audio = withContext(Dispatchers.IO) { spool(source, file) }
        return block(audio)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(file) }
    }
}

private fun spool(
    source: InputStream,
    file: Path,
): SpooledAudio {
    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    Files.newOutputStream(file).use { out ->
        source.forEachPage { page, n ->
            digest.update(page, 0, n)
            out.write(page, 0, n)
            sizeBytes += n
        }
    }
    return SpooledAudio(file, sizeBytes, HexFormat.of().formatHex(digest.digest()))
}

/** Inside the current transaction: inserts [taskId]'s `audio` row; a duplicate sha256 is rejected here (UNIQUE). */
internal fun JdbcTransaction.insertAudioRow(
    taskId: Long,
    audio: SpooledAudio,
) {
    AudioTable.insert {
        it[AudioTable.taskId] = taskId
        it[sizeBytes] = audio.sizeBytes
        it[sha256] = audio.sha256
    }
}

/**
 * Inside the current transaction: inserts the `audio` row first, so a duplicate sha256 is
 * rejected (UNIQUE) before any page is written, then one `audio_parts` row per page of [audio]'s file.
 */
internal fun JdbcTransaction.writeAudioRows(
    taskId: Long,
    audio: SpooledAudio,
) {
    insertAudioRow(taskId, audio)
    var partNo = 0
    Files.newInputStream(audio.file).use { input ->
        input.forEachPage { page, n ->
            // The insert executes before the next read, so a full page buffer is reused safely.
            val bytes = if (n == PAGE_SIZE) page else page.copyOf(n)
            AudioPartsTable.insert {
                it[AudioPartsTable.taskId] = taskId
                it[AudioPartsTable.partNo] = partNo
                it[part] = ExposedBlob(bytes)
            }
            partNo++
        }
    }
}

/**
 * Reads this stream to its end in windows of up to one page, calling [onWindow] with the shared
 * page buffer and the window's length; the buffer is reused for the next window.
 */
private inline fun InputStream.forEachPage(onWindow: (page: ByteArray, length: Int) -> Unit) {
    val page = ByteArray(PAGE_SIZE)
    while (true) {
        val n = readNBytes(page, 0, PAGE_SIZE)
        if (n == 0) break
        onWindow(page, n)
    }
}
