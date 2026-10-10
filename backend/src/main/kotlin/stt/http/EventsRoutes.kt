package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.engine.Event
import stt.engine.ProcessingEngine
import java.io.Writer

/**
 * The live events stream (ADR 0007, ADR 0038): `GET /api/events` speaks SSE by hand over Ktor's streaming
 * writer — every frame is `event: <name>\ndata: <json>\n\n`, flushed after each event, `Cache-Control:
 * no-cache`. The first frame is always `snapshot` (all tasks + the queue), so applying every event twice is
 * harmless and a reconnect needs no refetch; `task-updated` carries the full task.
 */
fun Application.eventsRoutes() {
    val engine: ProcessingEngine by dependencies
    val tasks: TaskRepository by dependencies
    val queueState: QueueStateRepository by dependencies
    routing {
        get("/api/events") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(
                contentType = ContentType("text", "event-stream"),
                status = HttpStatusCode.OK,
            ) {
                writeEvent(
                    "snapshot",
                    SseJson.encodeToString(
                        SnapshotJson(
                            tasks = tasks.listDetails().map { it.toJson() },
                            queue = QueueJson(paused = queueState.isPaused(), taskIds = tasks.pendingOrderedIds()),
                        ),
                    ),
                )
                flush()
                engine.events.collect { engineEvent ->
                    writeEvent(engineEvent.sseName(), engineEvent.sseData(tasks))
                    flush()
                }
            }
        }
    }
}

/** The body of the first SSE event (item 12's contract, `docs/api/snapshot.json`): everything at once. */
@Serializable
data class SnapshotJson(
    val tasks: List<TaskJson>,
    val queue: QueueJson,
)

/** The payload of `task-progress` (item 12's contract, `docs/api/task-progress.json`). */
@Serializable
data class TaskProgressJson(
    val id: Long,
    val transcriptionProgress: Double,
    val diarizationStage: String?,
    val diarizationProgress: Double?,
)

/** The JSON for the SSE event payloads: explicit nulls and defaults, so the shapes match `docs/api/`. */
private val SseJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

private suspend fun Event.sseName(): String =
    when (this) {
        is Event.TaskUpdated -> "task-updated"
        is Event.TaskProgress -> "task-progress"
        is Event.TaskDeleted -> "task-deleted"
        is Event.QueueUpdated -> "queue-updated"
    }

/** The event's `data:` JSON; the update event re-reads the task so its derived fields are current. */
private suspend fun Event.sseData(tasks: TaskRepository): String =
    when (this) {
        is Event.TaskUpdated -> {
            SseJson.encodeToString(tasks.details(task.id)!!.toJson())
        }

        is Event.TaskProgress -> {
            SseJson.encodeToString(
                TaskProgressJson(id, transcriptionProgress, diarizationStage?.name, diarizationProgress),
            )
        }

        is Event.TaskDeleted -> {
            SseJson.encodeToString(DeletedJson(id))
        }

        is Event.QueueUpdated -> {
            SseJson.encodeToString(QueueJson(paused, taskIds))
        }
    }

@Serializable
private data class DeletedJson(
    val id: Long,
)

/** One SSE frame; [data] is a single line (kotlinx serialization writes no newlines). */
private fun Writer.writeEvent(
    name: String,
    data: String,
) {
    append("event: $name\n")
    append("data: $data\n\n")
}
