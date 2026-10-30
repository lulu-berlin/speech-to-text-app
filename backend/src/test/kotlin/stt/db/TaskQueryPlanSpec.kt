package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TaskQueryPlanSpec :
    BehaviorSpec({

        given("the queue and list queries as the repository issues them") {
            `when`("their query plans are read on a database of 6 tasks, and again after it grows to 6 000") {
                then("no plan reads a table in full except the list's one pass over every task, and no plan changes") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        insertTasks(db, count = 6)
                        val small = queuePlans(db, tasks, this)
                        insertTasks(db, count = 5_994)
                        val large = queuePlans(db, tasks, this)

                        // SQLite reports a full pass over a table (or a whole index) as `SCAN <table>`, a lookup as
                        // `SEARCH`. Only the list reads every task, so its pass over `tasks` is the one expected scan.
                        small.mapValues { (_, plan) -> plan.filter { it.startsWith("SCAN ") } } shouldBe
                            mapOf(
                                "nextQueuePosition" to emptyList(),
                                "startedPaused" to emptyList(),
                                "pendingOrderedIds" to emptyList(),
                                "nextQueued" to emptyList(),
                                "retryIfPossible" to emptyList(),
                                "recoverRunning" to emptyList(),
                                "listDetails" to listOf("SCAN tasks"),
                            )
                        large shouldBe small
                    }
                }
            }
        }
    })

/**
 * Appends [count] tasks with their `audio` rows, cycling through the six statuses: QUEUED, RUNNING and PAUSED ones
 * hold a queue position (RUNNING and PAUSED ones have started), FAILED ones can be retried, and every COMPLETED one
 * has a stored clip header and two segments.
 */
private fun insertTasks(
    db: Database,
    count: Int,
) {
    db.connection.createStatement().use { statement ->
        statement.executeUpdate(
            """
            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $count)
            INSERT INTO tasks (original_filename, content_type, status, queue_position, speakers_mode, created_at, started_at)
            SELECT 'task.wav', 'audio/wav',
                   CASE i % 6 WHEN 0 THEN 'QUEUED' WHEN 1 THEN 'RUNNING' WHEN 2 THEN 'PAUSED'
                              WHEN 3 THEN 'COMPLETED' WHEN 4 THEN 'FAILED' ELSE 'CANCELED' END,
                   CASE WHEN i % 6 < 3 THEN i END, 'auto', i, CASE WHEN i % 6 IN (1, 2, 3) THEN i END
            FROM n
            """.trimIndent(),
        )
        statement.executeUpdate(
            "INSERT INTO audio (task_id, size_bytes, sha256) SELECT id, 1, 'sha-' || id FROM tasks " +
                "WHERE id NOT IN (SELECT task_id FROM audio)",
        )
        statement.executeUpdate(
            "INSERT INTO audio_header (task_id, header) SELECT id, x'00' FROM tasks " +
                "WHERE status = 'COMPLETED' AND id NOT IN (SELECT task_id FROM audio_header)",
        )
        statement.executeUpdate(
            "INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text) " +
                "SELECT id, 0, seq, seq * 1000, seq * 1000 + 900, 'x' FROM tasks, (SELECT 0 AS seq UNION ALL SELECT 1) " +
                "WHERE status = 'COMPLETED' AND id NOT IN (SELECT task_id FROM segments)",
        )
    }
}

/** Each queue and list query's plan lines (`EXPLAIN QUERY PLAN`'s detail), over every statement the call issues. */
private suspend fun queuePlans(
    db: Database,
    tasks: TaskRepository,
    dir: TestDataDir,
): Map<String, List<String>> {
    val failedId = singleValue(db, "SELECT min(id) FROM tasks WHERE status = 'FAILED'").toLong()
    val queries =
        listOf<Pair<String, suspend () -> Unit>>(
            "nextQueuePosition" to { tasks.nextQueuePosition() },
            "startedPaused" to { tasks.startedPaused() },
            "pendingOrderedIds" to { tasks.pendingOrderedIds() },
            "nextQueued" to { tasks.nextQueued() },
            "retryIfPossible" to { tasks.retryIfPossible(failedId) },
            "recoverRunning" to { tasks.recoverRunning() },
            "listDetails" to { tasks.listDetails() },
        )
    return queries.associate { (name, query) ->
        val (_, statements) = StatementCounter.recordStatements(dir.dataDir) { query() }
        name to statements.flatMap { queryPlan(db, it) }
    }
}

/** The detail lines SQLite's `EXPLAIN QUERY PLAN` reports for [sql], in order. */
private fun queryPlan(
    db: Database,
    sql: String,
): List<String> =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { rows ->
            buildList { while (rows.next()) add(rows.getString("detail")) }
        }
    }
