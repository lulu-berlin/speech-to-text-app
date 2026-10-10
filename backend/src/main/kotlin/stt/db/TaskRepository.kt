package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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
    ): Long = (createFromUploadOrDuplicate(task, upload) as UploadOutcome.Created).id

    /**
     * Spools [upload] once and decides with the spool's own digest: when [duplicateTaskId] finds a task
     * holding the same bytes, nothing is stored and [UploadOutcome.Duplicate] reports it; otherwise the task
     * is committed from the spool. The spool is deleted unless committed, whichever branch runs. No full
     * in-memory copy of the upload exists.
     */
    suspend fun createFromUploadOrDuplicate(
        task: Task,
        upload: InputStream,
        duplicateTaskId: suspend (String) -> Long? = { null },
    ): UploadOutcome = db.withSpooledUpload(upload) { spooled -> commitOrDuplicate(task, spooled, duplicateTaskId) }

    private suspend fun commitOrDuplicate(
        task: Task,
        spooled: SpooledUpload,
        duplicateTaskId: suspend (String) -> Long?,
    ): UploadOutcome =
        duplicateTaskId(spooled.sha256)?.let { UploadOutcome.Duplicate(it, spooled.sha256, spooled.sizeBytes) }
            ?: UploadOutcome.Created(createFromSpooled(task, spooled), spooled.sha256, spooled.sizeBytes)

    /**
     * Inserts [task] and its audio metadata from the spooled bytes, then moves the original into the task's
     * work directory. Runs under NonCancellable on Dispatchers.IO, so a canceled caller still gets its
     * original, and the whole move is on one filesystem (same data directory), so the rename is atomic.
     * Item 12's upload route calls this after spooling the part's channel itself ([Database.spoolChannel]).
     */
    internal suspend fun createFromSpooled(
        task: Task,
        spooled: SpooledUpload,
    ): Long {
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
        return taskId
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

    /**
     * The task with the facts the API reports beside its row ([TaskDetails]), read in one transaction so they
     * agree with each other. Every task is created with its upload's `audio` row ([createFromUpload]), so a
     * task is read together with it.
     */
    suspend fun details(id: Long): TaskDetails? =
        db.readTransaction {
            TasksTable
                .join(AudioTable, JoinType.INNER, TasksTable.id, AudioTable.taskId)
                .selectAll()
                .where { TasksTable.id eq id }
                .singleOrNull()
                ?.toDetails()
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
     * Every task with the facts the API reports beside its row ([TaskDetails]), read in one transaction so
     * the list is one consistent snapshot. Item 12's list route orders the result (pending tasks in queue
     * order, then finished tasks newest `finishedAt` first).
     */
    suspend fun listDetails(): List<TaskDetails> =
        db.readTransaction {
            TasksTable
                .join(AudioTable, JoinType.INNER, TasksTable.id, AudioTable.taskId)
                .selectAll()
                .map { row -> row.toDetails() }
                .sortedWith(ListOrder)
        }

    /**
     * The position of the next task appended to the queue: one after the highest `queue_position`, or 0 when
     * nothing is queued (item 12's upload contract: a new upload joins the end of the queue). Retry appends
     * with the same rule ([retryIfPossible]).
     */
    suspend fun nextQueuePosition(): Int =
        db.readTransaction {
            TasksTable
                .selectAll()
                .where { TasksTable.queuePosition.isNotNull() }
                .orderBy(TasksTable.queuePosition to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(TasksTable.queuePosition)
                ?.let { it + 1 }
                ?: 0
        }

    /**
     * The started PAUSED task, if any: one that once ran and is parked in a pause. It blocks the worker
     * (roadmap item 19) until it is resumed. The first in queue order stands in for "the" one.
     */
    suspend fun startedPaused(): StoredTask? =
        db.readTransaction {
            TasksTable
                .selectAll()
                .where { (TasksTable.status eq TaskStatus.PAUSED) and TasksTable.startedAt.isNotNull() }
                .orderBy(TasksTable.queuePosition to SortOrder.ASC_NULLS_LAST, TasksTable.id to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.toStoredTask()
        }

    /**
     * The ids of every non-terminal task in queue order (`queue_position`), as the queue event (item 12)
     * carries them.
     */
    suspend fun pendingOrderedIds(): List<Long> =
        db.readTransaction {
            TasksTable
                .selectAll()
                .where { TasksTable.status inList pendingStatuses }
                .orderBy(TasksTable.queuePosition to SortOrder.ASC_NULLS_LAST, TasksTable.id to SortOrder.ASC)
                .map { it[TasksTable.id] }
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
        updateTaskIf(id, TasksTable.status eq TaskStatus.QUEUED) {
            it[TasksTable.status] = TaskStatus.RUNNING
            it[TasksTable.startedAt] = startedAt
        }

    /** Stores the transcription progress (0-1, weighted by chunk duration). */
    suspend fun setProgress(
        id: Long,
        progress: Double,
    ) {
        updateTask(id) { it[TasksTable.progress] = progress }
    }

    /** Stores the diarization stage and its progress within that stage (item 12's contract; both null clear them). */
    suspend fun setDiarizationProgress(
        id: Long,
        stage: DiarizationStage?,
        progress: Double?,
    ) {
        updateTask(id) {
            it[TasksTable.diarizationStage] = stage
            it[TasksTable.diarizationProgress] = progress
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
        updateTaskIf(id, TasksTable.status inList cancellableFrom) {
            it[TasksTable.status] = TaskStatus.CANCELED
            it[TasksTable.finishedAt] = finishedAt
        }

    /**
     * Marks a RUNNING task FAILED with [error] and records when it finished. Only RUNNING can fail (item
     * 04's table), so a failure that races a cancel cannot overwrite CANCELED. Returns whether the row was
     * written; the phase the task stopped in stays (item 12's contract: "failed while transcribing").
     */
    suspend fun failIfRunning(
        id: Long,
        error: String,
        finishedAt: Long,
    ): Boolean =
        updateTaskIf(id, TasksTable.status eq TaskStatus.RUNNING) {
            it[TasksTable.status] = TaskStatus.FAILED
            it[TasksTable.error] = error
            it[TasksTable.finishedAt] = finishedAt
        }

    /** Writes [body] to task [id]'s row only while [condition] holds, inside the caller's transaction. */
    private fun updateTaskIfRow(
        id: Long,
        condition: Op<Boolean>,
        body: TasksTable.(UpdateStatement) -> Unit,
    ): Boolean = TasksTable.update({ (TasksTable.id eq id) and condition }) { body(it) } > 0

    /** Writes [body] to task [id]'s row only while [condition] holds; returns whether the row was written. */
    private suspend fun updateTaskIf(
        id: Long,
        condition: Op<Boolean>,
        body: TasksTable.(UpdateStatement) -> Unit,
    ): Boolean = db.writeTransaction { updateTaskIfRow(id, condition, body) }

    /**
     * Restart recovery (ADR 0004): marks every RUNNING task QUEUED again, keeping its `queue_position`,
     * and clears its phase (item 12's contract: a queued task has no phase). A crash leaves the row RUNNING
     * with its plan stored; the worker resumes it from the last DONE chunk. Returns how many rows were
     * rewritten.
     */
    suspend fun recoverRunning(): Int =
        db.writeTransaction {
            TasksTable.update({ TasksTable.status eq TaskStatus.RUNNING }) {
                it[TasksTable.status] = TaskStatus.QUEUED
                it[TasksTable.phase] = null
            }
        }

    /**
     * Retries a FAILED or CANCELED task: it is queued again at the end — one position after the highest
     * queued position today — and its error, `finished_at` and phase are cleared (item 12's contract), all
     * in one transaction so the position cannot collide. The engine checks item 04's table first, so a
     * COMPLETED task is never retried. Returns whether the row was written.
     */
    suspend fun retryIfPossible(id: Long): Boolean =
        db.writeTransaction {
            val last =
                TasksTable
                    .selectAll()
                    .where { TasksTable.queuePosition.isNotNull() }
                    .orderBy(TasksTable.queuePosition to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.get(TasksTable.queuePosition)
                    ?: -1
            updateTaskIfRow(id, TasksTable.status inList listOf(TaskStatus.FAILED, TaskStatus.CANCELED)) {
                it[TasksTable.status] = TaskStatus.QUEUED
                it[TasksTable.error] = null
                it[TasksTable.finishedAt] = null
                it[TasksTable.phase] = null
                it[TasksTable.queuePosition] = last + 1
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
        it[diarizationStage] = task.diarizationStage
        it[diarizationProgress] = task.diarizationProgress
        it[error] = task.error
        it[createdAt] = task.createdAt
        it[startedAt] = task.startedAt
        it[finishedAt] = task.finishedAt
    }[TasksTable.id]

private fun ResultRow.toStoredTask() = StoredTask(this[TasksTable.id], toTask())

/** The row (joined with `audio`) as the API's task view ([TaskDetails]). */
private fun ResultRow.toDetails(): TaskDetails {
    val id = this[TasksTable.id]
    val lastSegmentEnd = SegmentsTable.endMs.max()
    return TaskDetails(
        id = id,
        task = toTask(),
        sizeBytes = this[AudioTable.sizeBytes],
        hasAudio =
            !ChunkAudioTable
                .select(ChunkAudioTable.taskId)
                .where { ChunkAudioTable.taskId eq id }
                .empty(),
        transcribedUntilMs =
            SegmentsTable
                .select(lastSegmentEnd)
                .where { SegmentsTable.taskId eq id }
                .single()[lastSegmentEnd],
    )
}

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
        diarizationStage = this[TasksTable.diarizationStage],
        diarizationProgress = this[TasksTable.diarizationProgress],
        error = this[TasksTable.error],
        createdAt = this[TasksTable.createdAt],
        startedAt = this[TasksTable.startedAt],
        finishedAt = this[TasksTable.finishedAt],
    )

/** The non-terminal statuses: the queue holds exactly these. */
private val pendingStatuses = listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED)

/**
 * The item 12 list order: pending tasks (`QUEUED`, `RUNNING`, `PAUSED`) first, in queue order (ties by id),
 * then finished tasks newest `finishedAt` first. Pending tasks have no `finishedAt`, so a descending sort of
 * it must treat null as "oldest" — for them the pending-first key already decided the order.
 */
private val ListOrder: Comparator<TaskDetails> =
    Comparator { a: TaskDetails, b: TaskDetails ->
        val aPending = a.task.status in pendingStatuses
        val bPending = b.task.status in pendingStatuses
        when {
            aPending != bPending -> if (aPending) -1 else 1

            // Both pending: queue order, ties by id.
            aPending -> compareValuesBy(a, b) { it.task.queuePosition }.let { if (it != 0) it else a.id.compareTo(b.id) }

            // Both finished: newest `finishedAt` first; a null `finishedAt` counts as oldest (it only
            // occurs outside the pending set), achieved by ascending on the inverted value.
            else -> compareValuesBy(a, b) { Long.MAX_VALUE - (it.task.finishedAt ?: Long.MAX_VALUE) }
        }
    }

/** The statuses a task can be cancelled from (item 04's table: QUEUED, RUNNING, PAUSED). */
private val cancellableFrom = TaskStatus.entries.filter { it.canTransitionTo(TaskStatus.CANCELED) }
