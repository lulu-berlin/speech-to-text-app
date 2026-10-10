package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.Speaker
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.outputs.downloadFileName
import stt.outputs.renderSrt
import stt.outputs.renderTxt
import stt.outputs.renderVtt
import stt.queue.TaskStatus

/**
 * The transcript download (item 12): `GET /api/tasks/{id}/transcript?format=txt|srt|vtt`, rendered on demand
 * by item 10's pure renderers and served as an attachment (`Content-Disposition`, `downloadFileName`).
 * Only COMPLETED tasks download; any other status answers `409 not-completed`. Labels are the task's stored
 * speaker names (ADR 0034); a speakers-off task renders without labels.
 */
fun Application.transcriptRoutes() {
    val tasks: TaskRepository by dependencies
    val segments: SegmentRepository by dependencies
    routing {
        get("/api/tasks/{id}/transcript") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.notFound()
            val details = tasks.details(id) ?: return@get call.notFound()
            if (details.task.status != TaskStatus.COMPLETED) {
                return@get call.respondError(
                    HttpStatusCode.Conflict,
                    "not-completed",
                    "Task $id is ${details.task.status}; only COMPLETED tasks have a transcript",
                )
            }
            val requested = call.request.queryParameters["format"]
            val format =
                TRANSCRIPT_FORMATS[requested] ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-transcript-format",
                    "format must be one of ${TRANSCRIPT_FORMATS.keys.joinToString()}, got \"${requested ?: "(missing)"}\"",
                )
            val speakers = if (details.task.speakersMode == SpeakersMode.OFF) null else segments.findSpeakers(id)
            val content = format.render(segments.findByTask(id), speakers)
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${downloadFileName(details.task.originalFilename, format.extension)}\"",
            )
            call.respondBytes(content.toByteArray(), format.contentType)
        }
    }
}

private suspend fun RoutingCall.notFound() = respondError(HttpStatusCode.NotFound, "not-found", "Task ${parameters["id"]} does not exist")

/** One downloadable transcript format: its extension, its content type, and item 10's renderer. */
private class TranscriptFormat(
    val extension: String,
    val contentType: ContentType,
    val render: (List<Segment>, List<Speaker>?) -> String,
)

/** The downloads of item 12's contract (txt, srt, vtt; ADR 0035), keyed by the `format` query value. */
private val TRANSCRIPT_FORMATS: Map<String, TranscriptFormat> =
    listOf(
        TranscriptFormat("txt", ContentType.Text.Plain.withParameter("charset", "utf-8"), ::renderTxt),
        TranscriptFormat("srt", ContentType.parse("application/x-subrip"), ::renderSrt),
        TranscriptFormat("vtt", ContentType.parse("text/vtt"), ::renderVtt),
    ).associateBy { it.extension }
