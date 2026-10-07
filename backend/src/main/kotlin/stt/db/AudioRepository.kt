package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val PAGE_SIZE = 1_048_576

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
