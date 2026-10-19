package stt.db

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat

/** At most this many upload bytes are held in memory while staging; each window becomes one stored part. */
private const val STAGE_WINDOW_BYTES = 1_048_576

/**
 * An upload written into the database as a staged original ([OriginalAudioTable], item 44), with its size and
 * sha256. It belongs to no task until [TaskRepository.createFromStaged] attaches it.
 */
internal class StagedUpload(
    val originalId: Long,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Writes [source] into a staged original in windows of [STAGE_WINDOW_BYTES], each window in its own short write
 * transaction, computing size and sha256 on the way; runs [block] with it, and deletes the staged original
 * afterwards unless [block] attached it to a task, whether [block] or the copy succeeded or failed.
 */
internal suspend fun <T> Database.withStagedUpload(
    source: InputStream,
    block: suspend (StagedUpload) -> T,
): T {
    val originalId = stageOriginal()
    try {
        return block(stage(originalId, source))
    } finally {
        deleteUnclaimedOriginal(originalId)
    }
}

/**
 * Writes a multipart part's [ByteReadChannel] (item 12's upload route) into a staged original, reading in windows
 * of [STAGE_WINDOW_BYTES] — never the whole part at once — and computing size and sha256 on the way, so no full
 * in-memory copy of the upload exists and no file is written. Unlike [withStagedUpload] the caller owns the
 * staged original: it is attached to its task on commit, or discarded ([discardStaged]) when the request is
 * rejected. A failed read — including the upload outgrowing [maxBytes] (item 41) — discards it here.
 */
internal suspend fun Database.stageChannel(
    source: ByteReadChannel,
    maxBytes: Long = Long.MAX_VALUE,
): StagedUpload {
    val originalId = stageOriginal()
    try {
        return stage(originalId, source, maxBytes)
    } catch (e: Throwable) {
        deleteUnclaimedOriginal(originalId)
        throw e
    }
}

/** Deletes [upload]'s staged original and its bytes, unless a task has claimed it; a canceled caller still deletes. */
internal suspend fun Database.discardStaged(upload: StagedUpload) = deleteUnclaimedOriginal(upload.originalId)

/**
 * Deletes every staged original no task has claimed, with its parts (they cascade): what a crash mid-upload leaves.
 * The application runs it once at startup, as it opens its database and before it serves (`sttApp`), so no upload
 * in flight is touched.
 */
internal suspend fun Database.discardUnclaimedStaged() {
    writeTransaction { OriginalAudioTable.deleteWhere { OriginalAudioTable.taskId.isNull() } }
}

private suspend fun Database.stageOriginal(): Long =
    writeTransaction { OriginalAudioTable.insert { it[taskId] = null }[OriginalAudioTable.id] }

private suspend fun Database.deleteUnclaimedOriginal(originalId: Long) {
    withContext(NonCancellable) {
        writeTransaction {
            OriginalAudioTable.deleteWhere { (OriginalAudioTable.id eq originalId) and OriginalAudioTable.taskId.isNull() }
        }
    }
}

private suspend fun Database.appendPart(
    originalId: Long,
    seq: Int,
    bytes: ByteArray,
) {
    writeTransaction {
        OriginalAudioPartTable.insert {
            it[OriginalAudioPartTable.originalId] = originalId
            it[OriginalAudioPartTable.seq] = seq
            it[OriginalAudioPartTable.bytes] = ExposedBlob(bytes)
        }
    }
}

private suspend fun Database.stage(
    originalId: Long,
    source: ByteReadChannel,
    maxBytes: Long,
): StagedUpload {
    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    var seq = 0
    while (true) {
        // At most one window is in memory; the next read waits for more of the part (or EOF).
        val bytes = source.readBuffer(STAGE_WINDOW_BYTES.toLong()).readByteArray()
        if (bytes.isEmpty()) break
        // Rejected before a window that would cross the limit is written (item 41).
        if (sizeBytes + bytes.size > maxBytes) throw UploadTooLargeException()
        digest.update(bytes)
        appendPart(originalId, seq++, bytes)
        sizeBytes += bytes.size
    }
    return StagedUpload(originalId, sizeBytes, HexFormat.of().formatHex(digest.digest()))
}

private suspend fun Database.stage(
    originalId: Long,
    source: InputStream,
): StagedUpload {
    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    var seq = 0
    // Reused for every full window: a part's insert has finished before the next window is read into it.
    val window = ByteArray(STAGE_WINDOW_BYTES)
    while (true) {
        val n = withContext(Dispatchers.IO) { source.readNBytes(window, 0, STAGE_WINDOW_BYTES) }
        if (n == 0) break
        digest.update(window, 0, n)
        appendPart(originalId, seq++, if (n == STAGE_WINDOW_BYTES) window else window.copyOf(n))
        sizeBytes += n
    }
    return StagedUpload(originalId, sizeBytes, HexFormat.of().formatHex(digest.digest()))
}
