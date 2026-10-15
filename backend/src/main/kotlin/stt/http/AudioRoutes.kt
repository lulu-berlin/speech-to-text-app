package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import stt.db.CanonicalClipRepository
import stt.db.TaskRepository

/**
 * The audio download (item 12, item 44): `GET /api/tasks/{id}/audio` serves the task's canonical clip — the
 * stored 16 kHz mono FLAC (ADR 0024 replaced, a canonical FLAC is seekable as-is) — as `audio/flac`, straight
 * from the database: the header row plus its one-second rows in order are the encoded file byte for byte. No
 * join, no re-encode and no cache file exist; the database holds the only copy. A task without a canonical clip
 * (not chunked yet, or cancelled/failed before that) answers `404 no-audio`; the task's `hasAudio` tells the
 * client in advance. A single `Range` request (as browsers send for seeking) is answered with `206` and exactly
 * the requested bytes; an unsatisfiable one with `416`.
 */
fun Application.audioRoutes() {
    val tasks: TaskRepository by dependencies
    val clip: CanonicalClipRepository by dependencies
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
                    "Task ${details.id} has no stored audio",
                )
            }
            call.respondCanonicalAudio(details.id, clip)
        }
    }
}

/**
 * Serves the task's stored canonical clip, honoring one `Range` header (single range, `bytes=a-b`, `bytes=a-`,
 * `bytes=-n`; the case-sensitive `bytes=` unit is what clients send). Without a range the whole clip is served.
 * The body is streamed from the stored parts, so no range — however large — is buffered in heap; its length is
 * declared up front (`Content-Length`), so a client knows the download's size.
 */
private suspend fun RoutingCall.respondCanonicalAudio(
    taskId: Long,
    clip: CanonicalClipRepository,
) {
    val size = clip.size(taskId)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    val range = request.headers[HttpHeaders.Range]
    if (range == null) {
        response.header(HttpHeaders.ContentType, "audio/flac")
        respondOutputStream(ContentType("audio", "flac"), contentLength = size) { clip.copyTo(taskId, 0, size, this) }
        return
    }
    val slice = parseByteRange(range, size)
    if (slice == null) {
        response.header(HttpHeaders.ContentRange, "bytes */$size")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }
    val (start, end) = slice
    response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
    respondOutputStream(ContentType("audio", "flac"), HttpStatusCode.PartialContent, contentLength = end - start + 1) {
        clip.copyTo(taskId, start, end + 1, this)
    }
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
