package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
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

    /** Marks the task RUNNING and records when it started. */
    suspend fun setRunning(
        id: Long,
        startedAt: Long,
    ) {
        db.writeTransaction {
            TasksTable.update({ TasksTable.id eq id }) {
                it[TasksTable.status] = TaskStatus.RUNNING
                it[TasksTable.startedAt] = startedAt
            }
        }
    }

    /**
     * Sets the task's phase; null clears it. Per item 12's contract the phase is kept on FAILED and
     * CANCELED (the phase the task stopped in) and cleared when a task becomes QUEUED or COMPLETED.
     */
    suspend fun setPhase(
        id: Long,
        phase: TaskPhase?,
    ) {
        db.writeTransaction {
            TasksTable.update({ TasksTable.id eq id }) { it[TasksTable.phase] = phase }
        }
    }

    /** Stores the task's decoded duration, known once the source has been chunked. */
    suspend fun setDurationMs(
        id: Long,
        durationMs: Long,
    ) {
        db.writeTransaction {
            TasksTable.update({ TasksTable.id eq id }) { it[TasksTable.durationMs] = durationMs }
        }
    }

    /** Marks the task COMPLETED, clears its phase and records when it finished. */
    suspend fun setCompleted(
        id: Long,
        finishedAt: Long,
    ) {
        db.writeTransaction {
            TasksTable.update({ TasksTable.id eq id }) {
                it[TasksTable.status] = TaskStatus.COMPLETED
                it[TasksTable.phase] = null
                it[TasksTable.finishedAt] = finishedAt
            }
        }
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
