package stt.db.migration

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.audio.CANONICAL_FLAC_ARGS
import stt.audio.FFMPEG
import stt.audio.FlacRows
import stt.audio.FlacSplitter
import stt.audio.expectSuccess
import stt.audio.toCanonicalRows
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Item 44's data migration (ADR 0051): a task whose audio is still only its stored chunk FLACs (the
 * pre-item-44 shape, ADR 0024) gets the canonical clip the app now serves and diarizes from. Its chunks are
 * concatenated and re-encoded to the canonical 16 kHz mono FLAC (`-frame_size 4000`) in one ffmpeg run, then
 * split and stored as the `audio_header` row plus one `audio_second` row per whole second — exactly what the
 * engine stores for a task it chunks itself.
 *
 * Runs once, tracked in `flyway_schema_history`; a task that already has a header is skipped, so a re-run
 * after a repaired failure resumes. ffmpeg is needed because SQL cannot decode audio, and it runs through the
 * app's own [ProcessRunner] inside `runBlocking` (the same bridge the engine's DI uses): the migration is
 * blocking by contract, and it runs on Dispatchers.IO from [stt.db.Database.open]. A failure aborts the
 * upgrade with the data directory's migration error, as any other migration's would.
 *
 * Its temporary files live under [scratchRoot], inside the app's scratch directory ([stt.db.ScratchDir]): they
 * are removed as each task is done, and a kill mid-backfill leaves them where the next start empties them.
 */
class V10__BackfillCanonicalClip(
    private val scratchRoot: Path,
) : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        val tasks = legacyTaskIds(connection)
        if (tasks.isEmpty()) return
        logger.info("Rebuilding the canonical clip of {} task(s) stored as chunk FLACs", tasks.size)
        val scratch = Files.createDirectories(scratchRoot)
        try {
            tasks.forEach { taskId -> rebuild(connection, taskId, scratch.resolve(taskId.toString())) }
        } finally {
            deleteTree(scratch)
        }
    }

    /** Every task with stored chunk FLACs and no canonical clip yet, in id order. */
    private fun legacyTaskIds(connection: Connection): List<Long> =
        connection
            .prepareStatement(
                "SELECT DISTINCT c.task_id AS task_id FROM chunk_audio c " +
                    "WHERE NOT EXISTS (SELECT 1 FROM audio_header h WHERE h.task_id = c.task_id) ORDER BY c.task_id",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val ids = mutableListOf<Long>()
                    while (rows.next()) ids.add(rows.getLong("task_id"))
                    ids
                }
            }

    /**
     * Rebuilds one task's canonical clip from its chunk FLACs: written to [dir] in `idx` order, concatenated
     * and re-encoded by ffmpeg, split, stored, and the scratch removed (success or failure).
     */
    private fun rebuild(
        connection: Connection,
        taskId: Long,
        dir: Path,
    ) {
        try {
            val chunks = writeChunks(connection, taskId, dir)
            if (chunks.isEmpty()) return
            val list = dir.resolve("list.txt")
            Files.write(list, chunks.map { "file '${it.fileName}'" })
            val clip = dir.resolve("canonical.flac")
            val result =
                runBlocking {
                    ProcessRunner().run(
                        FFMPEG +
                            listOf("-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.toString()) +
                            CANONICAL_FLAC_ARGS +
                            listOf(clip.toString()),
                    )
                }
            result.expectSuccess("rebuild the canonical clip of task $taskId")
            store(connection, taskId, Files.readAllBytes(clip))
        } finally {
            deleteTree(dir)
        }
    }

    /** Writes the task's stored chunk FLACs into [dir] in `idx` order; returns the files. */
    private fun writeChunks(
        connection: Connection,
        taskId: Long,
        dir: Path,
    ): List<Path> {
        Files.createDirectories(dir)
        val chunks =
            connection.prepareStatement("SELECT idx, flac FROM chunk_audio WHERE task_id = ? ORDER BY idx").use { statement ->
                statement.setLong(1, taskId)
                statement.executeQuery().use { rows ->
                    val files = mutableListOf<Path>()
                    while (rows.next()) {
                        val file = dir.resolve("chunk-${rows.getInt("idx")}.flac")
                        Files.write(file, rows.getBytes("flac"))
                        files.add(file)
                    }
                    files
                }
            }
        return chunks
    }

    /** Splits the rebuilt canonical FLAC and stores its header and its one-second rows for [taskId]. */
    private fun store(
        connection: Connection,
        taskId: Long,
        clip: ByteArray,
    ) {
        val split = FlacSplitter.split(clip)
        connection.prepareStatement("INSERT INTO audio_header (task_id, header) VALUES (?, ?)").use { statement ->
            statement.setLong(1, taskId)
            statement.setBytes(2, split.header)
            statement.executeUpdate()
        }
        val rows = FlacRows.from(split).toCanonicalRows()
        connection.prepareStatement("INSERT INTO audio_second (task_id, second, frames, bytes) VALUES (?, ?, ?, ?)").use { statement ->
            rows.forEach { row ->
                statement.setLong(1, taskId)
                statement.setInt(2, row.second)
                statement.setInt(3, row.frames)
                statement.setBytes(4, row.bytes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(V10__BackfillCanonicalClip::class.java)
    }
}
