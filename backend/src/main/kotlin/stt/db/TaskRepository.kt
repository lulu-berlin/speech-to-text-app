package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import stt.queue.TaskStatus
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class TaskRepository(
    private val db: Database,
    private val workDirectories: TaskWorkDirectories,
) {
    /** Saves [task] and returns its generated id. */
    suspend fun insert(task: Task): Long = db.writeTransaction { insertTaskRow(task) }

    /**
     * Saves [task] for an [upload] (ADR 0024): the upload is spooled to a temp file first (no lock
     * held while it is read), then the task and its audio metadata are written in one short
     * transaction, so audio content that already exists (UNIQUE sha256) rejects the whole creation.
     * After the commit the spool file is moved to the task's original ([TaskWorkDirectories.source]),
     * so the task's `audio` row outlives the original; no audio bytes go into the database.
     * [upload] is read on Dispatchers.IO and not closed.
     *
     * If creating the work directory or moving the spool fails after the commit, the task is deleted
     * again — its `audio` row goes with it, so a retry with the same bytes is not rejected as a
     * duplicate — the work directory is removed best-effort, and the original exception is rethrown.
     */
    suspend fun createFromUpload(
        task: Task,
        upload: InputStream,
    ): Long =
        db.withSpooledUpload(upload) { spooled ->
            val taskId =
                db.writeTransaction {
                    insertTaskRow(task).also { taskId -> insertAudioRow(taskId, spooled) }
                }
            // Committed: the task's original must reach its work directory even if the caller is canceled now.
            try {
                moveToOriginal(taskId, spooled.file)
            } catch (e: Exception) {
                // The task row is committed by now; undo it, even for a canceled caller, so a rejected
                // upload leaves nothing behind: NonCancellable for the same reason the move uses it.
                withContext(NonCancellable) {
                    runCatching {
                        db.writeTransaction { TasksTable.deleteWhere { TasksTable.id eq taskId } }
                        deleteWorkDir(taskId)
                    }.onFailure { e.addSuppressed(it) }
                }
                throw e
            }
            taskId
        }

    /**
     * Creates the task's work directory and moves the committed upload's spool file into it as the
     * original. Runs under NonCancellable on Dispatchers.IO, so a canceled caller still gets its original,
     * and the whole move is on one filesystem (same data directory), so the rename is atomic.
     */
    private suspend fun moveToOriginal(
        taskId: Long,
        spool: Path,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            Files.createDirectories(workDirectories.workDir(taskId))
            Files.move(spool, workDirectories.source(taskId), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Removes a task's work directory and everything in it, best-effort: a failure is reported by the
     * caller's normal error path, never here. After the upload cleanup the database rows are already gone,
     * so a leftover directory cannot block a retry's `audio` row; item 11's engine cleanup is the backstop.
     */
    private suspend fun deleteWorkDir(taskId: Long) {
        try {
            withContext(Dispatchers.IO) {
                Files.list(workDirectories.workDir(taskId)).use { files -> files.forEach(Files::delete) }
                Files.delete(workDirectories.workDir(taskId))
            }
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    suspend fun find(id: Long): StoredTask? =
        db.readTransaction {
            TasksTable
                .selectAll()
                .where { TasksTable.id eq id }
                .singleOrNull()
                ?.toStoredTask()
        }

    /** All tasks in creation (id) order; reads only `tasks`, never audio. */
    suspend fun list(): List<StoredTask> =
        db.readTransaction {
            TasksTable
                .selectAll()
                .orderBy(TasksTable.id)
                .map { it.toStoredTask() }
        }

    /**
     * The QUEUED task with the lowest `queue_position` (ties broken by id), or null when none is queued.
     * The engine asks this before every task, so a stale in-memory order cannot start one out of turn.
     */
    suspend fun nextQueued(): StoredTask? =
        db.readTransaction {
            TasksTable
                .selectAll()
                .where { TasksTable.status eq TaskStatus.QUEUED }
                .orderBy(TasksTable.queuePosition to SortOrder.ASC_NULLS_LAST, TasksTable.id to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.toStoredTask()
        }

    /** Applies one `tasks` row update for [id] inside the caller's write transaction. */
    private fun updateTaskRow(
        id: Long,
        body: TasksTable.(UpdateStatement) -> Unit,
    ) {
        TasksTable.update({ TasksTable.id eq id }) { body(it) }
    }

    /** Applies one `tasks` row update for [id]; the single place that opens and addresses the row. */
    private suspend fun updateTask(
        id: Long,
        body: TasksTable.(UpdateStatement) -> Unit,
    ) {
        db.writeTransaction { updateTaskRow(id, body) }
    }

    /**
     * Writes the queue order: the first id in [ids] gets `queue_position` 0, the next 1, and so on, all in
     * one transaction so the worker can never read a half-written order. Item 12's `PUT /api/queue/order`
     * checks the id set with item 04's queue aggregate before calling this and then wakes the engine.
     */
    suspend fun setQueuePositions(ids: List<Long>) {
        db.writeTransaction {
            ids.forEachIndexed { position, id ->
                updateTaskRow(id) { it[TasksTable.queuePosition] = position }
            }
        }
    }

    /**
     * Writes the task's status alone. Whether the transition is allowed is the caller's business:
     * [stt.queue.TaskStatus.canTransitionTo] is item 04's table, and the engine checks it first.
     */
    suspend fun setStatus(
        id: Long,
        status: TaskStatus,
    ) {
        updateTask(id) { it[TasksTable.status] = status }
    }

    /**
     * The worker's start: marks the task RUNNING and records when it started, but only while it is still
     * QUEUED. A task cancelled (or paused) after the worker read it from the queue is left as it is, so
     * the cancel wins the race. Returns whether the row was written.
     */
    suspend fun setRunningIfQueued(
        id: Long,
        startedAt: Long,
    ): Boolean =
        db.writeTransaction {
            TasksTable.update({ (TasksTable.id eq id) and (TasksTable.status eq TaskStatus.QUEUED) }) {
                it[TasksTable.status] = TaskStatus.RUNNING
                it[TasksTable.startedAt] = startedAt
            } > 0
        }

    /**
     * Sets the task's phase; null clears it. Per item 12's contract the phase is kept on FAILED and
     * CANCELED (the phase the task stopped in) and cleared when a task becomes QUEUED or COMPLETED.
     */
    suspend fun setPhase(
        id: Long,
        phase: TaskPhase?,
    ) {
        updateTask(id) { it[TasksTable.phase] = phase }
    }

    /** Stores the task's decoded duration, known once the source has been chunked. */
    suspend fun setDurationMs(
        id: Long,
        durationMs: Long,
    ) {
        updateTask(id) { it[TasksTable.durationMs] = durationMs }
    }

    /** Marks the task COMPLETED, clears its phase and records when it finished. */
    suspend fun setCompleted(
        id: Long,
        finishedAt: Long,
    ) {
        updateTask(id) {
            it[TasksTable.status] = TaskStatus.COMPLETED
            it[TasksTable.phase] = null
            it[TasksTable.finishedAt] = finishedAt
        }
    }

    /**
     * Marks the task CANCELED and records when it finished, but only while its status still allows being
     * cancelled (item 04's table: QUEUED, RUNNING, PAUSED). A task that finished in the meantime keeps its
     * status, which is what makes a cancel racing a completion harmless. Returns whether the row was
     * written; the phase the task stopped in stays (item 12's contract).
     */
    suspend fun cancelIfPossible(
        id: Long,
        finishedAt: Long,
    ): Boolean =
        db.writeTransaction {
            TasksTable.update({ (TasksTable.id eq id) and (TasksTable.status inList cancellableFrom) }) {
                it[TasksTable.status] = TaskStatus.CANCELED
                it[TasksTable.finishedAt] = finishedAt
            } > 0
        }

    /** Deletes the task; its audio metadata, chunks with their chunk audio, and segments go with it (ON DELETE CASCADE). */
    suspend fun delete(id: Long) {
        db.writeTransaction { TasksTable.deleteWhere { TasksTable.id eq id } }
    }
}

/** Inserts the `tasks` row inside the current transaction and returns its generated id. */
internal fun JdbcTransaction.insertTaskRow(task: Task): Long =
    TasksTable.insert {
        it[originalFilename] = task.originalFilename
        it[contentType] = task.contentType
        it[durationMs] = task.durationMs
        it[status] = task.status
        it[phase] = task.phase
        it[queuePosition] = task.queuePosition
        it[language] = task.language
        it[speakersMode] = task.speakersMode
        it[speakersCount] = task.speakersCount
        it[maxSpeakers] = task.maxSpeakers
        it[model] = task.model
        it[progress] = task.progress
        it[error] = task.error
        it[createdAt] = task.createdAt
        it[startedAt] = task.startedAt
        it[finishedAt] = task.finishedAt
    }[TasksTable.id]

private fun ResultRow.toStoredTask() = StoredTask(this[TasksTable.id], toTask())

private fun ResultRow.toTask() =
    Task(
        originalFilename = this[TasksTable.originalFilename],
        contentType = this[TasksTable.contentType],
        durationMs = this[TasksTable.durationMs],
        status = this[TasksTable.status],
        phase = this[TasksTable.phase],
        queuePosition = this[TasksTable.queuePosition],
        language = this[TasksTable.language],
        speakersMode = this[TasksTable.speakersMode],
        speakersCount = this[TasksTable.speakersCount],
        maxSpeakers = this[TasksTable.maxSpeakers],
        model = this[TasksTable.model],
        progress = this[TasksTable.progress],
        error = this[TasksTable.error],
        createdAt = this[TasksTable.createdAt],
        startedAt = this[TasksTable.startedAt],
        finishedAt = this[TasksTable.finishedAt],
    )

/** The statuses a task can be cancelled from (item 04's table: QUEUED, RUNNING, PAUSED). */
private val cancellableFrom = TaskStatus.entries.filter { it.canTransitionTo(TaskStatus.CANCELED) }
