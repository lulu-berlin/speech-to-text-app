package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.Speaker
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.diarization.speakerTurns
import stt.outputs.downloadFileName
import stt.outputs.renderSrt
import stt.outputs.renderTxt
import stt.outputs.renderVtt
import stt.queue.TaskStatus

/**
 * The two views of a COMPLETED task's transcript (item 12); any other status answers `409 not-completed`.
 * - `GET /api/tasks/{id}/transcript?format=txt|srt|vtt`: rendered on demand by item 10's pure renderers and
 *   served as an attachment (`Content-Disposition`, `downloadFileName`). Labels are the stored speaker names
 *   (ADR 0034); a speakers-off task renders without labels.
 * - `GET /api/tasks/{id}/turns`: the stored speakers and item 09's `speakerTurns` with their segments, for the
 *   transcript view; a turn names its speaker only by `speakerId`. Speakers off: no speakers and one turn per
 *   segment with `speakerId` null. Texts are trimmed (ADR 0030).
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

        get("/api/tasks/{id}/turns") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.notFound()
            val details = tasks.details(id) ?: return@get call.notFound()
            if (details.task.status != TaskStatus.COMPLETED) {
                return@get call.respondError(
                    HttpStatusCode.Conflict,
                    "not-completed",
                    "Task $id is ${details.task.status}; only COMPLETED tasks have turns",
                )
            }
            val stored = segments.findByTask(id)
            if (details.task.speakersMode == SpeakersMode.OFF) {
                call.respond(TurnsJson(speakers = emptyList(), turns = stored.map { it.toNullSpeakerTurn() }))
            } else {
                val speakers = segments.findSpeakers(id)
                call.respond(
                    TurnsJson(
                        speakers = speakers.map { SpeakerJson(it.speakerId, it.speaker) },
                        turns =
                            speakerTurns(stored, speakers).map { turn ->
                                TurnJson(
                                    speakerId = turn.speakerId,
                                    startMs = turn.startMs,
                                    endMs = turn.endMs,
                                    text = turn.text,
                                    segments = turn.segments.map { TurnSegmentJson(it.startMs, it.endMs, it.text) },
                                )
                            },
                    ),
                )
            }
        }
    }
}

private suspend fun RoutingCall.notFound() = respondError(HttpStatusCode.NotFound, "not-found", "Task ${parameters["id"]} does not exist")

/** A speakers-off segment as its own turn (item 12's contract): no speaker, the trimmed text (ADR 0030). */
private fun Segment.toNullSpeakerTurn(): TurnJson {
    val trimmed = text.trim()
    return TurnJson(null, startMs, endMs, trimmed, listOf(TurnSegmentJson(startMs, endMs, trimmed)))
}

/** The body of `GET /api/tasks/{id}/turns` (item 12's contract, `docs/api/turns*.json`). */
@Serializable
data class TurnsJson(
    val speakers: List<SpeakerJson>,
    val turns: List<TurnJson>,
)

@Serializable
data class SpeakerJson(
    val speakerId: Int,
    val speaker: String,
)

@Serializable
data class TurnJson(
    val speakerId: Int?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val segments: List<TurnSegmentJson>,
)

@Serializable
data class TurnSegmentJson(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

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
