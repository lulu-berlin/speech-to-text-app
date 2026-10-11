package stt.http

import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import stt.audio.sineFlac
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

/**
 * Seeds a COMPLETED task with two stored sine-wave chunk FLACs (0.3 s at 440 Hz, then 0.5 s at 880 Hz) in
 * [dataDir] and returns its id. The finished state (item 11): the work directory is gone, but the chunks stay
 * in the database — so the real join must recreate its scratch location on the fly (item 29) — and its audio
 * must still serve (item 29's regression, item 37's runner isolation). The `/audio` specs share this setup;
 * [name] distinguishes several seeded tasks in one data dir (their chunk fixtures must not collide).
 */
internal suspend fun seedCompletedTaskWithChunks(
    dataDir: Path,
    name: String = "done",
): Long {
    val chunk0 = sineFlac(dataDir, "$name-0", "440", "0.3")
    val chunk1 = sineFlac(dataDir, "$name-1", "880", "0.5")
    return Database.open(dataDir).use { db ->
        val workDirectories = TaskWorkDirectories(dataDir)
        val stored =
            TaskRepository(db, workDirectories).createFromUpload(
                queuedTask("$name.wav", createdAt = 1).copy(
                    queuePosition = 0,
                    status = TaskStatus.COMPLETED,
                    finishedAt = 1_000,
                ),
                // Distinct bytes per seed: the app deduplicates audio by content sha256, so a second
                // seeded task must not repeat the first's upload payload.
                "audio bytes for $name".toByteArray().inputStream(),
            )
        ChunkRepository(db).storePlan(
            stored,
            listOf(
                ExportedChunk(PlannedChunk(0L, 300L), chunk0),
                ExportedChunk(PlannedChunk(300L, 800L), chunk1),
            ),
        )
        workDirectories.workDir(stored).toFile().deleteRecursively()
        stored
    }
}
