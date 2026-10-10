package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.audio.AudioJoiner
import stt.db.TaskRepository
import java.io.File
import java.io.RandomAccessFile

/**
 * The audio download (item 12): `GET /api/tasks/{id}/audio` serves the task's stored chunk FLACs joined into
 * one FLAC (`audio/flac`, 16 kHz mono, ADR 0024), built once into the `tmp/<task-id>.flac` cache. A task
 * without stored chunks (not chunked yet, or cancelled/failed before that) answers `404 no-audio`; the task's
 * `hasAudio` tells the client in advance. A single `Range` request (as browsers send for seeking) is answered
 * with `206` and exactly the requested bytes; an unsatisfiable one with `416`.
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
            call.respondAudioFile(file.toFile())
        }
    }
}

/**
 * Serves the cached joined FLAC, honoring one `Range` header (single range, `bytes=a-b`, `bytes=a-`, `bytes=-n`;
 * the case-sensitive `bytes=` unit is what clients send). Without a range the whole file is served as before.
 */
private suspend fun RoutingCall.respondAudioFile(file: File) {
    val size = file.length()
    response.header(HttpHeaders.AcceptRanges, "bytes")
    val range = request.headers[HttpHeaders.Range]
    if (range == null) {
        response.header(HttpHeaders.ContentType, "audio/flac")
        respondFile(file)
        return
    }
    val slice = parseByteRange(range, size)
    if (slice == null) {
        response.header(HttpHeaders.ContentRange, "bytes */$size")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }
    val (start, end) = slice
    val bytes =
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                ByteArray((end - start + 1).toInt()).also { raf.readFully(it) }
            }
        }
    response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
    respondBytes(bytes, ContentType("audio", "flac"), HttpStatusCode.PartialContent)
}

/** Parses one `bytes` range against [size], or null when malformed or outside the file (→ `416`). */
private fun parseByteRange(
    range: String,
    size: Long,
): Pair<Long, Long>? {
    val spec = range.removePrefix("bytes=")
    if (spec == range || ',' in spec || size == 0L) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startText = spec.substring(0, dash)
    val endText = spec.substring(dash + 1)
    return when {
        startText.isEmpty() -> {
            // `bytes=-n`: the last n bytes.
            val n = endText.toLongOrNull() ?: return null
            if (n <= 0) return null
            (size - n).coerceAtLeast(0) to (size - 1)
        }

        else -> {
            val start = startText.toLongOrNull() ?: return null
            if (start >= size) return null
            val end = endText.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (end < start) return null
            start to end
        }
    }
}
