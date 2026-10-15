package stt.http

import stt.db.CanonicalAudio
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.db.storeClip
import stt.queue.TaskStatus
import java.nio.file.Path

/**
 * A hand-built canonical clip: a header and two one-second rows. Item 44's repository serves the stored parts
 * as they are, byte for byte, so a spec can use recognizable patterns instead of a real FLAC. Shared by the
 * `/audio` specs.
 */
internal class TestClip(
    val header: ByteArray,
    val rows: List<CanonicalAudio>,
) {
    val bytes =
        ByteArray(header.size + rows.sumOf { it.bytes.size }).also { out ->
            var offset = 0
            header.copyInto(out, offset)
            offset += header.size
            rows.forEach { row ->
                row.bytes.copyInto(out, offset)
                offset += row.bytes.size
            }
        }
}

/** A small canonical clip whose parts are recognizable byte patterns: a 10-byte header then 90 bytes of rows. */
internal fun testClip() =
    TestClip(
        header = ByteArray(10) { it.toByte() },
        rows =
            listOf(
                CanonicalAudio(second = 0, frames = 4, bytes = ByteArray(40) { (it + 10).toByte() }),
                CanonicalAudio(second = 1, frames = 4, bytes = ByteArray(50) { (it + 50).toByte() }),
            ),
    )

/** A COMPLETED task created from a small upload named [filename] (distinct names avoid the sha256 dedupe). */
internal suspend fun completedTask(
    db: Database,
    filename: String,
): Long =
    TaskRepository(db).createFromUpload(
        queuedTask(filename, createdAt = 1).copy(
            queuePosition = 0,
            status = TaskStatus.COMPLETED,
            finishedAt = 1_000,
        ),
        "audio bytes of $filename".toByteArray().inputStream(),
    )

/**
 * Opens [dataDir]'s database with the queue paused and stores a COMPLETED task named [filename] with [clip] as its
 * canonical clip; [also] adds whatever else a spec needs to that task before the database closes. Returns its id.
 */
internal suspend fun taskWithTestClip(
    dataDir: Path,
    clip: TestClip,
    filename: String = "memo.wav",
    also: suspend (Database, Long) -> Unit = { _, _ -> },
): Long =
    Database.open(dataDir).use { db ->
        QueueStateRepository(db).setPaused(true)
        val id = completedTask(db, filename)
        storeClip(db, id, clip.header, clip.rows)
        also(db, id)
        id
    }
