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
import stt.db.SpeakersMode
import stt.db.Task
import stt.db.TaskRepository
import stt.queue.TaskStatus
import java.io.ByteArrayInputStream
import java.nio.file.Path

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part. The route validates the
 * request shape, then [TaskRepository.createFromUpload] spools the part, stores the task with its audio
 * metadata in one transaction, and moves the original into the task's work directory (ADR 0024; no audio
 * bytes reach the database). Answers `201` with the created task.
 */
fun Application.uploadRoutes(config: Config) {
    val tasks: TaskRepository by dependencies
    routing {
        post("/api/tasks") {
            var filename: String? = null
            var contentType: String? = null
            var upload: ByteArray? = null
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        filename = part.originalFileName
                        contentType = part.contentType?.toString()
                        upload = part.provider().readRemaining().readByteArray()
                    }

                    else -> {
                        Unit
                    }
                }
                part.dispose()
            }
            val file =
                upload ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "missing-file",
                    "No file part named \"file\" in the multipart body",
                )
            val task =
                Task(
                    originalFilename = filename ?: "upload",
                    contentType = contentType ?: "application/octet-stream",
                    durationMs = null,
                    status = TaskStatus.QUEUED,
                    phase = null,
                    // The contract: a new upload joins the end of the queue.
                    queuePosition = tasks.nextQueuePosition(),
                    language = "auto",
                    speakersMode = SpeakersMode.AUTO,
                    speakersCount = null,
                    maxSpeakers = 8,
                    model = modelName(config.whisperModel),
                    progress = 0.0,
                    diarizationStage = null,
                    diarizationProgress = 0.0,
                    error = null,
                    createdAt = System.currentTimeMillis(),
                    startedAt = null,
                    finishedAt = null,
                )
            val id = tasks.createFromUpload(task, ByteArrayInputStream(file))
            call.respond(HttpStatusCode.Created, tasks.details(id)!!.toJson())
        }
    }
}

/** The model name stored on the task (item 12): the whisper model file's stem without a `ggml-` prefix. */
private fun modelName(whisperModel: Path): String =
    whisperModel.fileName.toString().let { file ->
        file.removePrefix("ggml-").substringBeforeLast('.')
    }
