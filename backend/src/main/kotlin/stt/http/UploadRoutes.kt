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
import stt.db.SpooledUpload
import stt.db.TaskRepository
import stt.db.spoolChannel
import stt.engine.ProcessingEngine
import java.nio.file.Files

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part and the optional form fields
 * `language`, `speakers` and `maxSpeakers`. The file part is consumed into the spool at parse time, window by
 * window ([Database.spoolChannel], ADR 0024) — never read fully into memory — and [UploadRequest] validates
 * the request afterwards; a rejection is a 400 with its error code and the spool is discarded. A valid
 * request is decided with the spool's own digest: the bytes are already known → `409 duplicate-audio` naming
 * the existing task (nothing stored); otherwise the task and its audio metadata are committed in one
 * transaction ([TaskRepository.createFromSpooled]) and the original moved into the task's work directory (no
 * audio bytes reach the database). A created task wakes the engine ([ProcessingEngine.wake]) so it is taken
 * without waiting for another command. Answers `201` with the created task.
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
            var spooled: SpooledUpload? = null
            val fields = mutableMapOf<String, String>()
            val multipart = call.receiveMultipart()
            while (true) {
                val part = multipart.readPart() ?: break
                when (part) {
                    is PartData.FileItem -> {
                        filename = part.originalFileName
                        contentType = part.contentType?.toString()
                        // Consumed here, in windows, so the channel is fully read while the part is alive.
                        spooled = db.spoolChannel(part.provider())
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
                            spooled ?: return@post call.respondError(
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
                            val id = tasks.createFromSpooled(task, file)
                            // A new upload is an engine event: the worker suspends on its wake channel while
                            // the queue is empty, so it must be woken here or the task stays QUEUED until the
                            // next command (item 29).
                            engine.wake()
                            call.respond(HttpStatusCode.Created, tasks.details(id)!!.toJson())
                        }
                    }
                }
            } finally {
                // Deletes the spool unless createFromSpooled already moved it into the task's work directory.
                spooled?.let { Files.deleteIfExists(it.file) }
            }
        }
    }
}
