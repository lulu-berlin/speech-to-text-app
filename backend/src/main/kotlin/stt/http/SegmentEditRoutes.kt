package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.SegmentRepository
import stt.db.SegmentWriteOutcome
import stt.db.TaskRepository
import stt.engine.ProcessingEngine

/**
 * Editing a transcript segment (item 52): `PUT /api/tasks/{id}/segments/{segmentId}` with `{ "text": "…" }` stores
 * the text on that stored segment — an in-place update of the row, nothing else — and answers `200` with the task's
 * updated turns (`docs/api/turns.json`), the same body `GET /api/tasks/{id}/turns` returns. Only a COMPLETED task is
 * editable (`409 not-completed` otherwise, item 22's rule); an unknown task or a segment that is not the task's
 * answers `404 not-found`.
 */
fun Application.segmentEditRoutes() {
    val engine: ProcessingEngine by dependencies
    val tasks: TaskRepository by dependencies
    val segments: SegmentRepository by dependencies
    routing {
        put("/api/tasks/{id}/segments/{segmentId}") {
            val details = call.completedTaskOrError(tasks, "segments to edit") ?: return@put
            val segmentId =
                call.parameters["segmentId"]?.toLongOrNull()
                    ?: return@put call.respondNoSegment(details.id)
            val request = call.receive<EditSegmentRequest>()
            if (!request.text.isValidSegmentText()) {
                return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-segment-text",
                    "segment text must not be empty, whitespace-only or contain control characters",
                )
            }
            when (engine.editSegment(details.id, segmentId, request.text)) {
                is SegmentWriteOutcome.Stored -> call.respond(segments.turnsJson(details))
                SegmentWriteOutcome.NoSegment -> call.respondNoSegment(details.id)
                SegmentWriteOutcome.NotCompleted -> return@put call.respondNotCompleted(details.id, "segments to edit")
            }
        }
        // ADR 0061: put whisper's original text back on one segment, answering the updated turns like an edit.
        post("/api/tasks/{id}/segments/{segmentId}/revert") {
            val details = call.completedTaskOrError(tasks, "segments to revert") ?: return@post
            val segmentId =
                call.parameters["segmentId"]?.toLongOrNull()
                    ?: return@post call.respondNoSegment(details.id)
            when (engine.revertSegment(details.id, segmentId)) {
                is SegmentWriteOutcome.Stored -> call.respond(segments.turnsJson(details))
                SegmentWriteOutcome.NoSegment -> call.respondNoSegment(details.id)
                SegmentWriteOutcome.NotCompleted -> return@post call.respondNotCompleted(details.id, "segments to revert")
            }
        }
    }
}

/** `404 not-found` for a segment id that is not one of the task's stored segments. */
private suspend fun RoutingCall.respondNoSegment(taskId: Long) =
    respondError(
        HttpStatusCode.NotFound,
        "not-found",
        "Task $taskId has no segment ${parameters["segmentId"]}",
    )

/** The body of the edit endpoint: the new text of the segment. */
@Serializable
private data class EditSegmentRequest(
    val text: String,
)

/** Whether [text] can be stored as sent: not empty or whitespace-only, and without a control character anywhere. */
private fun String.isValidSegmentText(): Boolean = isNotBlank() && none(Char::isISOControl)
