package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.MergeOutcome
import stt.db.RenameOutcome
import stt.db.SegmentRepository
import stt.db.Speaker
import stt.db.TaskRepository
import stt.db.defaultSpeakerName
import stt.engine.ProcessingEngine

/**
 * Renaming a task's speakers (item 22): `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": "<name>" }`
 * stores the name as sent and answers `200` with the task's speakers (`docs/api/speakers.json`). Clients trim
 * (user decision 2026-10-10), so a name with leading or trailing whitespace, an empty one, one longer than
 * [MAX_SPEAKER_NAME] characters, or one with a control character anywhere answers `400 invalid-speaker-name` and
 * changes nothing; a name another speaker of the task already has, in any letter case, answers
 * `409 duplicate-speaker-name`. `{ "speaker": null }` resets the name to the default `"Speaker N"`
 * (N = speakerId + 1, item 09's rule), which is checked for duplicates like any other name. A stored rename or
 * reset goes through the engine, which publishes `speakers-renamed` after the write (item 11's event flow).
 *
 * Merging (item 22): `POST /api/tasks/{id}/speakers/merge` with `{ "group": [<speakerId>, …], "into": <speakerId> }`
 * folds every other member of the group into `into` through the merge mapping (no segment row is rewritten) and
 * answers `200` with the task's effective speakers. `DELETE /api/tasks/{id}/speakers/merges` ("Reset all merges")
 * removes every merge of the task and answers `200` with all its speakers; both publish `speakers-merged`.
 */
fun Application.speakerRoutes() {
    val engine: ProcessingEngine by dependencies
    val tasks: TaskRepository by dependencies
    val segments: SegmentRepository by dependencies
    routing {
        put("/api/tasks/{id}/speakers/{speakerId}") {
            val id = call.completedTaskOrError(tasks, "speakers to rename")?.id ?: return@put
            val speakerId = call.parameters["speakerId"]?.toIntOrNull()
            // Only a shown speaker can be renamed: one hidden by a merge answers 404 too (user decision 2026-10-10).
            if (speakerId == null || segments.findDiarized(id).speakers.none { it.speakerId == speakerId }) {
                return@put call.respondError(
                    HttpStatusCode.NotFound,
                    "not-found",
                    "Task $id has no speaker ${call.parameters["speakerId"]}",
                )
            }
            val requested = call.receive<RenameSpeakerRequest>().speaker
            if (requested != null && !isValidSpeakerName(requested)) {
                return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-speaker-name",
                    "speaker must be 1–$MAX_SPEAKER_NAME characters, without leading or trailing whitespace or control characters",
                )
            }
            val name = requested ?: defaultSpeakerName(speakerId)
            when (val outcome = engine.renameSpeaker(id, speakerId, name)) {
                RenameOutcome.Renamed -> {
                    call.respondSpeakers(segments, id)
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

        post("/api/tasks/{id}/speakers/merge") {
            val id = call.completedTaskOrError(tasks, "speakers to merge")?.id ?: return@post
            val request = call.receive<MergeSpeakersRequest>()
            val known = segments.findSpeakers(id).map { it.speakerId }.toSet()
            (request.group + request.into).firstOrNull { it !in known }?.let {
                return@post call.respondError(HttpStatusCode.NotFound, "not-found", "Task $id has no speaker $it")
            }
            when (val outcome = engine.mergeSpeakers(id, request.group, request.into)) {
                MergeOutcome.Merged -> {
                    call.respondSpeakers(segments, id)
                }

                is MergeOutcome.Invalid -> {
                    call.respondError(HttpStatusCode.BadRequest, "invalid-merge", outcome.reason)
                }
            }
        }

        delete("/api/tasks/{id}/speakers/merges") {
            val id = call.completedTaskOrError(tasks, "merges to reset")?.id ?: return@delete
            engine.resetMerges(id)
            call.respondSpeakers(segments, id)
        }
    }
}

/** Answers `200` with the task's effective speakers (`docs/api/speakers.json`), read through its merges. */
private suspend fun RoutingCall.respondSpeakers(
    segments: SegmentRepository,
    id: Long,
) = respond(SpeakersJson(segments.findDiarized(id).speakers.map { it.toJson() }))

/** The longest speaker name (item 22). */
private const val MAX_SPEAKER_NAME = 40

/** Whether [name] can be stored as sent: already trimmed, 1–[MAX_SPEAKER_NAME] characters, no control character. */
private fun isValidSpeakerName(name: String): Boolean =
    name.length in 1..MAX_SPEAKER_NAME && name == name.trim() && name.none(Char::isISOControl)

/** The body of `PUT /api/tasks/{id}/speakers/{speakerId}`; a null [speaker] resets the name to its default. */
@Serializable
private data class RenameSpeakerRequest(
    val speaker: String?,
)

/** The body of `POST /api/tasks/{id}/speakers/merge`: the speakers to join, and the one that remains. */
@Serializable
private data class MergeSpeakersRequest(
    val group: List<Int>,
    val into: Int,
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

fun Speaker.toJson(): SpeakerJson = SpeakerJson(speakerId, speaker)
