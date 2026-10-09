package stt.engine

import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import java.nio.file.Files
import java.nio.file.Path

/** Inserts a QUEUED task at [queuePosition] with an original in its work directory, as an upload does (ADR 0024). */
internal suspend fun enqueueTask(
    tasks: TaskRepository,
    workDirectories: TaskWorkDirectories,
    filename: String,
    queuePosition: Int,
    createdAt: Long,
): Long {
    val id =
        tasks.insert(
            queuedTask(filename, createdAt).copy(queuePosition = queuePosition, speakersMode = SpeakersMode.OFF),
        )
    val source = workDirectories.source(id)
    Files.createDirectories(source.parent)
    Files.writeString(source, "fake source audio")
    return id
}

/** The id of the task a work path belongs to: `<dataDir>/work/<id>[/…]` (ADR 0024). */
internal fun taskIdOf(workPath: Path): Long = workPath.fileName.toString().toLong()
