package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.SegmentRepository

/**
 * Renaming a task's speakers (item 22): `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": "<name>" }`
 * stores the trimmed name and answers `200` with the task's speakers (`docs/api/speakers.json`). A name that is
 * empty after trimming, longer than [MAX_SPEAKER_NAME] characters, or contains a control character answers
 * `400 invalid-speaker-name` and changes nothing.
 */
fun Application.speakerRoutes() {
    val segments: SegmentRepository by dependencies
    routing {
        put("/api/tasks/{id}/speakers/{speakerId}") {
            val id = call.parameters["id"]!!.toLong()
            val speakerId = call.parameters["speakerId"]!!.toInt()
            val name =
                storedSpeakerName(call.receive<RenameSpeakerRequest>().speaker) ?: return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-speaker-name",
                    "speaker must be 1–$MAX_SPEAKER_NAME characters after trimming, without control characters",
                )
            segments.renameSpeaker(id, speakerId, name)
            call.respond(SpeakersJson(segments.findSpeakers(id).map { SpeakerJson(it.speakerId, it.speaker) }))
        }
    }
}

/** The longest speaker name, counted after trimming (item 22). */
private const val MAX_SPEAKER_NAME = 40

/** The name as stored: [requested] trimmed, 1–[MAX_SPEAKER_NAME] characters, no control character; null when invalid. */
private fun storedSpeakerName(requested: String): String? =
    requested.trim().takeIf { it.length in 1..MAX_SPEAKER_NAME && it.none(Char::isISOControl) }

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
