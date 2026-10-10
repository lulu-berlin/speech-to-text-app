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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.engine.Event
import java.io.Writer

/**
 * The live events stream (ADR 0007, ADR 0038): `GET /api/events` speaks SSE by hand over Ktor's streaming
 * writer — every frame is `event: <name>\ndata: <json>\n\n`, flushed after each event, `Cache-Control:
 * no-cache`. The first frame is always `snapshot` (all tasks + the queue), so applying every event twice is
 * harmless and a reconnect needs no refetch; `task-updated` carries the full task.
 */
fun Application.eventsRoutes() {
    val stream: EventStream by dependencies
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
                try {
                    pumpEvents(stream.events) { engineEvent ->
                        writeEvent(engineEvent.sseName(), engineEvent.sseData(tasks))
                        flush()
                    }
                } catch (_: FellBehindException) {
                    // Deliberate end of the stream; nothing more to write.
                }
            }
        }
    }
}

/**
 * Writes every event of [events] to [writeFrame]. A missing sequence number means this subscriber's buffer
 * overflowed and events were dropped for it (user decision 2026-10-10): pumping throws [FellBehindException]
 * and the SSE response ends, so EventSource reconnects and the snapshot makes the client whole again.
 */
internal suspend fun pumpEvents(
    events: Flow<Pair<Long, Event>>,
    writeFrame: suspend (Event) -> Unit,
) {
    var lastSeen = -1L
    events.collect { (sequence, engineEvent) ->
        if (lastSeen != -1L && sequence != lastSeen + 1) throw FellBehindException()
        writeFrame(engineEvent)
        lastSeen = sequence
    }
}

/** Signals that this subscriber lost events and the SSE response must end. */
internal class FellBehindException : Exception()

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
        is Event.SpeakersRenamed -> "speakers-renamed"
        is Event.SpeakersMerged -> "speakers-merged"
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

        is Event.SpeakersRenamed -> {
            SseJson.encodeToString(SpeakersEventJson(id, speakers.map { it.toJson() }))
        }

        is Event.SpeakersMerged -> {
            SseJson.encodeToString(SpeakersEventJson(id, speakers.map { it.toJson() }))
        }
    }

@Serializable
private data class DeletedJson(
    val id: Long,
)

/** The payload of `speakers-renamed` and `speakers-merged` (item 22, `docs/api/speakers-*.json`): the task and its speakers. */
@Serializable
data class SpeakersEventJson(
    val id: Long,
    val speakers: List<SpeakerJson>,
)

/** One SSE frame; [data] is a single line (kotlinx serialization writes no newlines). */
private fun Writer.writeEvent(
    name: String,
    data: String,
) {
    append("event: $name\n")
    append("data: $data\n\n")
}
