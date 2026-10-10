package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.RenameOutcome
import stt.db.SegmentRepository

/**
 * Renaming a task's speakers (item 22): `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": "<name>" }`
 * stores the name as sent and answers `200` with the task's speakers (`docs/api/speakers.json`). Clients trim
 * (user decision 2026-10-10), so a name with leading or trailing whitespace, an empty one, one longer than
 * [MAX_SPEAKER_NAME] characters, or one with a control character anywhere answers `400 invalid-speaker-name` and
 * changes nothing; a name another speaker of the task already has, in any letter case, answers
 * `409 duplicate-speaker-name`.
 */
fun Application.speakerRoutes() {
    val segments: SegmentRepository by dependencies
    routing {
        put("/api/tasks/{id}/speakers/{speakerId}") {
            val id = call.parameters["id"]!!.toLong()
            val speakerId = call.parameters["speakerId"]!!.toInt()
            val name = call.receive<RenameSpeakerRequest>().speaker
            if (!isValidSpeakerName(name)) {
                return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-speaker-name",
                    "speaker must be 1–$MAX_SPEAKER_NAME characters, without leading or trailing whitespace or control characters",
                )
            }
            when (val outcome = segments.renameSpeaker(id, speakerId, name)) {
                RenameOutcome.Renamed -> {
                    call.respond(SpeakersJson(segments.findSpeakers(id).map { SpeakerJson(it.speakerId, it.speaker) }))
                }

                is RenameOutcome.DuplicateName -> {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "duplicate-speaker-name",
                        "Speaker ${outcome.existing.speakerId} of task $id is already named \"${outcome.existing.speaker}\"",
                    )
                }
            }
        }
    }
}

/** The longest speaker name (item 22). */
private const val MAX_SPEAKER_NAME = 40

/** Whether [name] can be stored as sent: already trimmed, 1–[MAX_SPEAKER_NAME] characters, no control character. */
private fun isValidSpeakerName(name: String): Boolean =
    name.length in 1..MAX_SPEAKER_NAME && name == name.trim() && name.none(Char::isISOControl)

/** The body of `PUT /api/tasks/{id}/speakers/{speakerId}`. */
@Serializable
private data class RenameSpeakerRequest(
    val speaker: String,
)

/** The body of the speaker endpoints' `200` (item 22, `docs/api/speakers.json`): the task's speakers by `speakerId`. */
@Serializable
data class SpeakersJson(
    val speakers: List<SpeakerJson>,
)

/** One speaker of a task (ADR 0028): `speakerId` the 0-based number, `speaker` the stored name; used by `/turns` too. */
@Serializable
data class SpeakerJson(
    val speakerId: Int,
    val speaker: String,
)
