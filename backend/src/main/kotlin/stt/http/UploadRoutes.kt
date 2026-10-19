package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import stt.config.Config
import stt.db.AudioRepository
import stt.db.Database
import stt.db.StagedUpload
import stt.db.TaskRepository
import stt.db.UploadOutcome
import stt.db.UploadTooLargeException
import stt.db.discardStaged
import stt.db.stageChannel
import stt.engine.ProcessingEngine

/** The `413 upload-too-large` of item 41: the message names the limit in binary units. */
private suspend fun RoutingCall.respondTooLarge(limit: Long) =
    respondError(
        HttpStatusCode.PayloadTooLarge,
        "upload-too-large",
        "The upload exceeds the maximum size of ${humanBytes(limit)}",
    )

/** The `409 duplicate-audio` body of item 12's contract, naming [existingTaskId]. */
private fun duplicateAudio(existingTaskId: Long) =
    DuplicateAudioError(
        error = "duplicate-audio",
        message = "This file was already uploaded as task $existingTaskId",
        taskId = existingTaskId,
    )

/**
 * Test-only seam (item 41): runs inside the multipart loop right after a file part was staged — the point where a
 * client disconnect cancels the handler between parts, which the Ktor test client cannot reach. Unset in
 * production.
 */
internal var afterFilePartStaged: (suspend () -> Unit)? = null

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part and the optional form fields
 * `language`, `speakers` and `maxSpeakers`. The file part is consumed into the database at parse time, window by
 * window ([Database.stageChannel], item 44) — never read fully into memory, never written to a file — and
 * [UploadRequest] validates the request afterwards; a rejection is a 400 with its error code and the staged
 * original is discarded. A valid request is decided with the upload's own digest: the bytes are already known →
 * `409 duplicate-audio` naming the existing task (nothing stored); otherwise the task, its audio metadata and its
 * original are committed in one transaction ([TaskRepository.createFromStaged]), and a concurrent request that
 * committed the same bytes first is answered with the same 409 (item 41). A created task is announced and
 * taken at once ([ProcessingEngine.taskCreated]:
 * `task-updated` and `queue-updated` on the events stream, then the worker is woken). Answers `201` with the
 * created task.
 */
fun Application.uploadRoutes(config: Config) {
    val tasks: TaskRepository by dependencies
    val audio: AudioRepository by dependencies
    val engine: ProcessingEngine by dependencies
    val db: Database by dependencies
    routing {
        post("/api/tasks") {
            val limit = config.maxUploadBytes
            // A declared body size over the limit is refused before a single part is read (item 41).
            call.request.contentLength()?.let { declared ->
                if (declared > limit) return@post call.respondTooLarge(limit)
            }
            var filename: String? = null
            var contentType: String? = null
            var staged: StagedUpload? = null
            val fields = mutableMapOf<String, String>()
            val multipart = call.receiveMultipart()
            try {
                while (true) {
                    val part = multipart.readPart() ?: break
                    when (part) {
                        is PartData.FileItem -> {
                            if (staged != null) {
                                // One file per upload (item 41): the first staged upload is discarded, never kept.
                                db.discardStaged(staged)
                                return@post call.respondError(
                                    HttpStatusCode.BadRequest,
                                    "multiple-files",
                                    "Only one file may be uploaded per request",
                                )
                            }
                            filename = part.originalFileName
                            contentType = part.contentType?.toString()
                            // Consumed here, in windows, so the channel is fully read while the part is alive.
                            staged =
                                try {
                                    db.stageChannel(part.provider(), limit)
                                } catch (e: UploadTooLargeException) {
                                    // stageChannel discarded what it staged; the request is refused.
                                    return@post call.respondTooLarge(limit)
                                }
                            // Test-only seam (item 41); a client disconnect cancels the loop right here.
                            afterFilePartStaged?.invoke()
                        }

                        is PartData.FormItem -> {
                            fields[part.name ?: ""] = part.value
                        }

                        else -> {
                            Unit
                        }
                    }
                    part.dispose()
                }

                when (val request = UploadRequest(filename, contentType, fields).build(config)) {
                    is UploadRequest.Either.Rejected -> {
                        call.respondError(HttpStatusCode.BadRequest, request.code, request.message)
                    }

                    is UploadRequest.Either.Valid -> {
                        // A Valid request always carried a file part (UploadRequest rejects a missing one).
                        val file =
                            staged ?: return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                "missing-file",
                                "No file part named \"file\" in the multipart body",
                            )
                        val existingTaskId = audio.taskIdOfSha256(file.sha256)
                        if (existingTaskId != null) {
                            call.respond(HttpStatusCode.Conflict, duplicateAudio(existingTaskId))
                        } else {
                            // The contract places a new upload at the end of the queue.
                            val task =
                                request.task.copy(
                                    createdAt = System.currentTimeMillis(),
                                    queuePosition = tasks.nextQueuePosition(),
                                )
                            when (val outcome = tasks.createFromStaged(task, file)) {
                                is UploadOutcome.Created -> {
                                    // A new upload is an engine event: clients learn of it only from the events
                                    // stream, and the worker suspends while the queue is empty, so it must be woken
                                    // here (item 29).
                                    engine.taskCreated(outcome.id)
                                    call.respond(HttpStatusCode.Created, tasks.details(outcome.id)!!.toJson())
                                }

                                is UploadOutcome.Duplicate -> {
                                    // Another request committed the same bytes between the check and the insert.
                                    call.respond(HttpStatusCode.Conflict, duplicateAudio(outcome.existingId))
                                }
                            }
                        }
                    }
                }
            } finally {
                // Discards whatever was staged and no task claimed, on every path (item 41).
                staged?.let { db.discardStaged(it) }
            }
        }
    }
}
