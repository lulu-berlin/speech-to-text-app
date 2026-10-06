package stt.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.InputStream

class TaskRepository(
    private val db: Database,
) {
    /** Saves [task] and returns its generated id. */
    suspend fun insert(task: Task): Long = db.transaction { insertTaskRow(task) }

    /**
     * Saves [task] and streams [audio] into its pages and metadata in one transaction,
     * so audio content that already exists (UNIQUE sha256) rejects the whole creation.
     */
    suspend fun createWithAudio(
        task: Task,
        audio: InputStream,
    ): Long =
        db.transaction {
            val taskId = insertTaskRow(task)
            writeAudioRows(taskId, audio)
            taskId
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

    /** Deletes the task; its audio, pages, chunks, and segments go with it (ON DELETE CASCADE). */
    suspend fun delete(id: Long) {
        db.transaction { TasksTable.deleteWhere { TasksTable.id eq id } }
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
