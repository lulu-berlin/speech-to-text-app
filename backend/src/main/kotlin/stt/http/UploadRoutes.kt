package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import stt.config.Config
import stt.db.AudioRepository
import stt.db.TaskRepository
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part and the optional form fields
 * `language`, `speakers` and `maxSpeakers`. [UploadRequest] validates the request and builds the task; a
 * rejection is a 400 with its error code. The accepted file part is streamed into [TaskRepository.createFromUpload],
 * which spools it, stores the task with its audio metadata in one transaction, and moves the original into the
 * task's work directory (ADR 0024; no audio bytes reach the database). Answers `201` with the created task.
 */
fun Application.uploadRoutes(config: Config) {
    val tasks: TaskRepository by dependencies
    val audio: AudioRepository by dependencies
    routing {
        post("/api/tasks") {
            var filename: String? = null
            var contentType: String? = null
            var upload: ByteArray? = null
            val fields = mutableMapOf<String, String>()
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        filename = part.originalFileName
                        contentType = part.contentType?.toString()
                        upload = part.provider().readRemaining().readByteArray()
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
                    // A Valid request always carried a file part (UploadRequest rejects a missing one);
                    // the contract places a new upload at the end of the queue.
                    val bytes = upload!!
                    val existingTaskId = audio.taskIdOfSha256(sha256Hex(bytes))
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
                        val task =
                            request.task.copy(
                                createdAt = System.currentTimeMillis(),
                                queuePosition = tasks.nextQueuePosition(),
                            )
                        val id = tasks.createFromUpload(task, ByteArrayInputStream(bytes))
                        call.respond(HttpStatusCode.Created, tasks.details(id)!!.toJson())
                    }
                }
            }
        }
    }
}

/** The lowercase hex SHA-256 of [bytes], the same digest the spool computes (UploadSpool). */
private fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
