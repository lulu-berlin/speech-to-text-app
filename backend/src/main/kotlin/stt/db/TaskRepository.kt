package stt.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class TaskRepository(
    private val db: Database,
) {
    /** Saves [task] and returns its generated id. */
    suspend fun insert(task: Task): Long =
        db.transaction {
            TasksTable.insert {
                it[originalFilename] = task.originalFilename
                it[contentType] = task.contentType
                it[durationMs] = task.durationMs
                it[status] = task.status
                it[phase] = task.phase
                it[queuePosition] = task.queuePosition
                it[language] = task.language
                it[speakersMode] = task.speakersMode.dbValue
                it[speakersCount] = task.speakersCount
                it[maxSpeakers] = task.maxSpeakers
                it[model] = task.model
                it[progress] = task.progress
                it[error] = task.error
                it[createdAt] = task.createdAt
                it[startedAt] = task.startedAt
                it[finishedAt] = task.finishedAt
            }[TasksTable.id]
        }

    suspend fun find(id: Long): Task? =
        db.transaction {
            TasksTable
                .selectAll()
                .where { TasksTable.id eq id }
                .singleOrNull()
                ?.toTask()
        }
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
        speakersMode = SpeakersMode.fromDbValue(this[TasksTable.speakersMode]),
        speakersCount = this[TasksTable.speakersCount],
        maxSpeakers = this[TasksTable.maxSpeakers],
        model = this[TasksTable.model],
        progress = this[TasksTable.progress],
        error = this[TasksTable.error],
        createdAt = this[TasksTable.createdAt],
        startedAt = this[TasksTable.startedAt],
        finishedAt = this[TasksTable.finishedAt],
    )
