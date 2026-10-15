package stt.db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Item 44's give-up on audio that could not be migrated (user decision 2026-10-15, ADR 0051): before item 44 a
 * task's original waited in `$STT_DATA_DIR/work/<id>/source` until chunking, and V10 rebuilds the clip only of tasks
 * that were chunked. A task left with neither a stored clip nor a stored original can never run again, so every such
 * task that is not COMPLETED ends FAILED with an error telling the user to delete it and upload the file again (its
 * finish time kept when it has one). Then the pre-item-44 `work/` and `tmp/` directories under [dataDir] are removed:
 * nothing reads them any more.
 *
 * Runs once, after V10 and V11, tracked in `flyway_schema_history`; `Database.open` registers it with the data
 * directory. Removing the directories is a side effect Flyway cannot roll back, and harmless: nothing reads them.
 */
class V12__GiveUpUnmigratedTasks(
    private val dataDir: Path,
) : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val failed =
            context.connection
                .prepareStatement(
                    "UPDATE tasks SET status = 'FAILED', error = ?, finished_at = COALESCE(finished_at, ?) " +
                        "WHERE status <> 'COMPLETED' " +
                        "AND id NOT IN (SELECT task_id FROM audio_header) " +
                        "AND id NOT IN (SELECT task_id FROM original_audio WHERE task_id IS NOT NULL)",
                ).use { statement ->
                    statement.setString(1, NOT_MIGRATED)
                    statement.setLong(2, System.currentTimeMillis())
                    statement.executeUpdate()
                }
        if (failed > 0) logger.info("Gave up on {} task(s) whose audio from before the upgrade was not migrated", failed)
        listOf("work", "tmp").forEach { deleteTree(dataDir.resolve(it)) }
    }

    private companion object {
        /** The error a task keeps when its audio from before the upgrade could not be migrated. */
        const val NOT_MIGRATED = "This task's audio from before the upgrade was not migrated. Delete the task and upload the file again."

        val logger: Logger = LoggerFactory.getLogger(V12__GiveUpUnmigratedTasks::class.java)
    }
}
