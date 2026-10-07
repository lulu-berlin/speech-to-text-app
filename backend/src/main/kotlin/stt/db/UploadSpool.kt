package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** At most this many upload bytes are held in memory while spooling. */
private const val SPOOL_WINDOW_BYTES = 1_048_576

/** An upload copied to a temp file under `$STT_DATA_DIR/tmp`, with its size and sha256. */
internal class SpooledUpload(
    val file: Path,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Copies [source] to a temp file under [Database.tmpDir] in windows of [SPOOL_WINDOW_BYTES], computing its size
 * and sha256 on the way, runs [block] with it, and deletes the file afterwards unless [block] moved
 * it away, whether [block] or the copy succeeded or failed. No database lock is held while the source is read.
 */
internal suspend fun <T> Database.withSpooledUpload(
    source: InputStream,
    block: suspend (SpooledUpload) -> T,
): T {
    val file = withContext(Dispatchers.IO) { Files.createTempFile(tmpDir, "upload-", ".part") }
    try {
        val upload = withContext(Dispatchers.IO) { spool(source, file) }
        return block(upload)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(file) }
    }
}

private fun spool(
    source: InputStream,
    file: Path,
): SpooledUpload {
    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    Files.newOutputStream(file).use { out ->
        source.forEachWindow { window, n ->
            digest.update(window, 0, n)
            out.write(window, 0, n)
            sizeBytes += n
        }
    }
    return SpooledUpload(file, sizeBytes, HexFormat.of().formatHex(digest.digest()))
}

/**
 * Reads this stream to its end in windows of up to [SPOOL_WINDOW_BYTES], calling [onWindow] with the shared
 * buffer and the window's length; the buffer is reused for the next window.
 */
private inline fun InputStream.forEachWindow(onWindow: (window: ByteArray, length: Int) -> Unit) {
    val window = ByteArray(SPOOL_WINDOW_BYTES)
    while (true) {
        val n = readNBytes(window, 0, SPOOL_WINDOW_BYTES)
        if (n == 0) break
        onWindow(window, n)
    }
}
