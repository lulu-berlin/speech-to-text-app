package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import stt.config.Config
import stt.db.AudioRepository
import stt.db.Database
import stt.db.StagedUpload
import stt.db.TaskRepository
import stt.db.discardStaged
import stt.db.stageChannel
import stt.engine.ProcessingEngine

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part and the optional form fields
 * `language`, `speakers` and `maxSpeakers`. The file part is consumed into the database at parse time, window by
 * window ([Database.stageChannel], item 44) — never read fully into memory, never written to a file — and
 * [UploadRequest] validates the request afterwards; a rejection is a 400 with its error code and the staged
 * original is discarded. A valid request is decided with the upload's own digest: the bytes are already known →
 * `409 duplicate-audio` naming the existing task (nothing stored); otherwise the task, its audio metadata and its
 * original are committed in one transaction ([TaskRepository.createFromStaged]). A created task is announced and
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
            var filename: String? = null
            var contentType: String? = null
            var staged: StagedUpload? = null
            val fields = mutableMapOf<String, String>()
            val multipart = call.receiveMultipart()
            while (true) {
                val part = multipart.readPart() ?: break
                when (part) {
                    is PartData.FileItem -> {
                        filename = part.originalFileName
                        contentType = part.contentType?.toString()
                        // Consumed here, in windows, so the channel is fully read while the part is alive.
                        staged = db.stageChannel(part.provider())
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

            try {
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
                            call.respond(
                                HttpStatusCode.Conflict,
                                DuplicateAudioError(
                                    error = "duplicate-audio",
                                    message = "This file was already uploaded as task $existingTaskId",
                                    taskId = existingTaskId,
                                ),
                            )
                        } else {
                            // The contract places a new upload at the end of the queue.
                            val task =
                                request.task.copy(
                                    createdAt = System.currentTimeMillis(),
                                    queuePosition = tasks.nextQueuePosition(),
                                )
                            val id = tasks.createFromStaged(task, file)
                            // A new upload is an engine event: clients learn of it only from the events stream,
                            // and the worker suspends while the queue is empty, so it must be woken here (item 29).
                            engine.taskCreated(id)
                            call.respond(HttpStatusCode.Created, tasks.details(id)!!.toJson())
                        }
                    }
                }
            } finally {
                // Discards the staged original unless createFromStaged already attached it to its task.
                staged?.let { db.discardStaged(it) }
            }
        }
    }
}
