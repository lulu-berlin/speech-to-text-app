package stt.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import stt.audio.AudioJoiner
import stt.db.TaskRepository

/**
 * The audio download (item 12): `GET /api/tasks/{id}/audio` serves the task's stored chunk FLACs joined into
 * one FLAC (`audio/flac`, 16 kHz mono, ADR 0024), built once into the `tmp/<task-id>.flac` cache. A task
 * without stored chunks (not chunked yet, or cancelled/failed before that) answers `404 no-audio`; the task's
 * `hasAudio` tells the client in advance. HTTP Range support is added by the next list item.
 */
fun Application.audioRoutes() {
    val tasks: TaskRepository by dependencies
    val joiner: AudioJoiner by dependencies
    val cache: JoinedAudioCache by dependencies
    routing {
        get("/api/tasks/{id}/audio") {
            val id = call.parameters["id"]?.toLongOrNull()
            val details = id?.let { tasks.details(it) }
            if (details == null) {
                return@get call.respondError(
                    HttpStatusCode.NotFound,
                    "not-found",
                    "Task ${call.parameters["id"]} does not exist",
                )
            }
            if (!details.hasAudio) {
                return@get call.respondError(
                    HttpStatusCode.NotFound,
                    "no-audio",
                    "Task $id has no stored chunk audio",
                )
            }
            val file = cache.getOrBuild(details.id) { target -> joiner.join(details.id, target) }
            call.response.header(HttpHeaders.ContentType, "audio/flac")
            call.respondFile(file.toFile())
        }
    }
}
