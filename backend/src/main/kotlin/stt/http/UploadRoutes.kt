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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.config.Config
import stt.db.SpeakersMode
import stt.db.Task
import stt.db.TaskRepository
import stt.queue.TaskStatus
import java.io.ByteArrayInputStream
import java.nio.file.Path

/**
 * The upload endpoint (item 12): a multipart POST /api/tasks with a `file` part and the optional form fields
 * `language`, `speakers` (`auto|off|<n>`) and `maxSpeakers` (1–20). The request is validated against the
 * contract first — every rejection is a 400 with its error code — then [TaskRepository.createFromUpload]
 * spools the part, stores the task with its audio metadata in one transaction, and moves the original into
 * the task's work directory (ADR 0024; no audio bytes reach the database). Answers `201` with the created task.
 */
fun Application.uploadRoutes(config: Config) {
    val tasks: TaskRepository by dependencies
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

            val language = fields["language"] ?: "auto"
            if (!LANGUAGES.contains(language)) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-language",
                    "Language \"$language\" is not in docs/api/languages.json",
                )
            }

            val speakersMode: SpeakersMode
            val speakersCount: Int?
            when (val speakers = fields["speakers"]) {
                null, "auto" -> {
                    speakersMode = SpeakersMode.AUTO
                    speakersCount = null
                }

                "off" -> {
                    speakersMode = SpeakersMode.OFF
                    speakersCount = null
                }

                else -> {
                    val count = speakers.toIntOrNull()
                    if (count == null || count !in 1..MAX_SPEAKERS) {
                        return@post call.respondError(
                            HttpStatusCode.BadRequest,
                            "invalid-speakers",
                            "speakers must be auto, off, or a number between 1 and $MAX_SPEAKERS, got \"$speakers\"",
                        )
                    }
                    speakersMode = SpeakersMode.FIXED
                    speakersCount = count
                }
            }

            val maxSpeakers = fields["maxSpeakers"]?.toIntOrNull() ?: 8
            if (maxSpeakers !in 1..MAX_SPEAKERS) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-max-speakers",
                    "maxSpeakers must be a number between 1 and $MAX_SPEAKERS, got \"${fields["maxSpeakers"]}\"",
                )
            }

            val file =
                upload ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "missing-file",
                    "No file part named \"file\" in the multipart body",
                )
            val extension = filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
            if (extension == null || extension !in ALLOWED_EXTENSIONS) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "unsupported-format",
                    "\"$filename\" is not a supported audio or video file (allowed: $ALLOWED_EXTENSIONS_LIST)",
                )
            }

            val task =
                Task(
                    originalFilename = filename ?: "upload",
                    contentType = contentType ?: "application/octet-stream",
                    durationMs = null,
                    status = TaskStatus.QUEUED,
                    phase = null,
                    // The contract: a new upload joins the end of the queue.
                    queuePosition = tasks.nextQueuePosition(),
                    language = language,
                    speakersMode = speakersMode,
                    speakersCount = speakersCount,
                    maxSpeakers = maxSpeakers,
                    model = modelName(config.whisperModel),
                    progress = 0.0,
                    diarizationStage = null,
                    diarizationProgress = if (speakersMode == SpeakersMode.OFF) null else 0.0,
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

/** The extensions the upload accepts, checked case-insensitively; the content type is ignored (item 12). */
private val ALLOWED_EXTENSIONS: Set<String> =
    setOf(
        "mp3",
        "wav",
        "flac",
        "ogg",
        "oga",
        "opus",
        "m4a",
        "aac",
        "wma",
        "aif",
        "aiff",
        "amr",
        "webm",
        "mp4",
        "m4v",
        "mov",
        "mkv",
        "mka",
        "3gp",
    )
private const val ALLOWED_EXTENSIONS_LIST = "mp3 wav flac ogg oga opus m4a aac wma aif aiff amr webm mp4 m4v mov mkv mka 3gp"

/** The upper bound for a fixed speaker count and for `maxSpeakers` (item 12). */
private const val MAX_SPEAKERS = 20

/** The language codes of `docs/api/languages.json`, packaged under `src/main/resources` (parity: LanguagesContractSpec). */
private val LANGUAGES: Set<String> =
    Json
        .parseToJsonElement(
            Thread
                .currentThread()
                .contextClassLoader
                .getResource("languages.json")!!
                .readText(),
        ).jsonObject
        .getValue("languages")
        .jsonArray
        .map {
            it.jsonObject
                .getValue("code")
                .jsonPrimitive.content
        }.toSet()

/** The model name stored on the task (item 12): the whisper model file's stem without a `ggml-` prefix. */
private fun modelName(whisperModel: Path): String =
    whisperModel.fileName.toString().let { file ->
        file.removePrefix("ggml-").substringBeforeLast('.')
    }
