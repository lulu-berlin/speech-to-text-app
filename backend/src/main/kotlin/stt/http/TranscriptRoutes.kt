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
import stt.db.TaskDetails
import stt.db.TaskRepository
import stt.diarization.SpeakerTurn
import stt.diarization.TurnSegment
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
 *   (ADR 0034), a merged speaker's segments under its survivor's name (item 22); a speakers-off task renders
 *   without labels.
 * - `GET /api/tasks/{id}/turns`: the effective speakers and item 09's `speakerTurns` with their segments, for the
 *   transcript view; a turn names its speaker only by `speakerId`. Speakers off: no speakers and one turn per
 *   segment with `speakerId` null. Texts are trimmed (ADR 0030).
 *
 * With speakers on, both read [SegmentRepository.findDiarized], the one place a task's merges are resolved.
 */
fun Application.transcriptRoutes() {
    val tasks: TaskRepository by dependencies
    val segments: SegmentRepository by dependencies
    routing {
        get("/api/tasks/{id}/transcript") {
            val details = call.completedTaskOrError(tasks, "a transcript") ?: return@get
            val id = details.id
            val requested = call.request.queryParameters["format"]
            val format =
                TRANSCRIPT_FORMATS[requested] ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-transcript-format",
                    "format must be one of ${TRANSCRIPT_FORMATS.keys.joinToString()}, got \"${requested ?: "(missing)"}\"",
                )
            // Item 22: with speakers on, the renderers get the segments as merged and the effective speakers;
            // the renderers themselves are unchanged.
            val content =
                if (details.task.speakersMode == SpeakersMode.OFF) {
                    format.render(segments.findByTask(id), null)
                } else {
                    val diarized = segments.findDiarized(id)
                    format.render(diarized.segments, diarized.speakers)
                }
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${downloadFileName(details.task.originalFilename, format.extension)}\"",
            )
            call.respondBytes(content.toByteArray(), format.contentType)
        }

        get("/api/tasks/{id}/turns") {
            val details = call.completedTaskOrError(tasks, "turns") ?: return@get
            call.respond(segments.turnsJson(details))
        }
    }
}

/**
 * The body of `GET /api/tasks/{id}/turns` and of a segment edit's `200` (item 52, `docs/api/turns*.json`): with
 * speakers off, one turn per segment with no speaker; otherwise the effective speakers (item 22) and item 09's
 * turns, whose segments name their stored row by `id`.
 */
internal suspend fun SegmentRepository.turnsJson(details: TaskDetails): TurnsJson {
    val id = details.id
    if (details.task.speakersMode == SpeakersMode.OFF) {
        val turns = findByTask(id).map { it.toNullSpeakerTurn() }
        return TurnsJson(speakers = emptyList(), hasMerges = false, turns = turns)
    }
    // Item 22: the effective speakers and the segments as merged; speakerTurns itself is unchanged.
    val diarized = findDiarized(id)
    return TurnsJson(
        speakers = diarized.speakers.map { it.toJson() },
        hasMerges = diarized.hasMerges,
        turns = speakerTurns(diarized.segments, diarized.speakers).map { it.toJson() },
    )
}

private suspend fun RoutingCall.notFound() = respondError(HttpStatusCode.NotFound, "not-found", "Task ${parameters["id"]} does not exist")

/** `409 not-completed`: the task is not COMPLETED (any more), so it has no [what] to give. */
internal suspend fun RoutingCall.respondNotCompleted(
    taskId: Long,
    what: String,
    status: TaskStatus? = null,
) = respondError(
    HttpStatusCode.Conflict,
    "not-completed",
    if (status == null) {
        "Task $taskId left COMPLETED; only COMPLETED tasks have $what"
    } else {
        "Task $taskId is $status; only COMPLETED tasks have $what"
    },
)

/**
 * The route's task when it exists and is COMPLETED; otherwise responds `404 not-found` (missing or non-numeric
 * id, unknown task) or `409 not-completed` (naming what only a COMPLETED task has) and returns null.
 */
internal suspend fun RoutingCall.completedTaskOrError(
    tasks: TaskRepository,
    what: String,
): TaskDetails? {
    val details = parameters["id"]?.toLongOrNull()?.let { tasks.details(it) }
    if (details == null) {
        notFound()
        return null
    }
    if (details.task.status != TaskStatus.COMPLETED) {
        respondNotCompleted(details.id, what, details.task.status)
        return null
    }
    return details
}

/** A speakers-off segment as its own turn (item 12's contract): no speaker, the trimmed text (ADR 0030). */
private fun Segment.toNullSpeakerTurn(): TurnJson {
    val segment = toJson()
    return TurnJson(null, startMs, endMs, segment.text, listOf(segment))
}

/** One stored segment as the contract names it (item 52), its text trimmed for every reader (ADR 0030). */
internal fun Segment.toJson(): TurnSegmentJson =
    TurnSegmentJson(
        requireNotNull(id) { "Segment has no stored row id" },
        startMs,
        endMs,
        text.trim(),
        // ADR 0061: whether an edit moved the text away from whisper's line; both trimmed, as readers see them.
        edited = text.trim() != (originalText ?: text).trim(),
    )

/** The body of `GET /api/tasks/{id}/turns` (item 12's contract, `docs/api/turns*.json`). */
@Serializable
data class TurnsJson(
    val speakers: List<SpeakerJson>,
    // Item 22: whether the task has a merge to undo ("Reset all merges"); false with speakers off.
    val hasMerges: Boolean,
    val turns: List<TurnJson>,
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
    // Item 52: the stored segment row the edit endpoint addresses.
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    // ADR 0061: whether the stored text differs from whisper's original, so a client offers Revert.
    val edited: Boolean,
)

/** One turn as the contract names it (item 12's contract); its segments carry their stored row ids (item 52). */
internal fun SpeakerTurn.toJson(): TurnJson =
    TurnJson(
        speakerId = speakerId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        segments = segments.map { it.toJson() },
    )

/** One turn segment as the contract names it; every segment read from the database carries its row id (item 52). */
internal fun TurnSegment.toJson(): TurnSegmentJson =
    TurnSegmentJson(requireNotNull(id) { "Turn segment has no stored row id" }, startMs, endMs, text, edited)

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
