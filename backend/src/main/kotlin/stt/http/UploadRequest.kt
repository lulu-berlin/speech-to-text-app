package stt.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.config.Config
import stt.db.SpeakersMode
import stt.db.Task
import stt.queue.TaskStatus
import java.nio.file.Path

/**
 * One validated upload request (item 12's contract): the file part's filename and content type and the form
 * fields `language` (`auto` default, validated against [docs/api/languages.json](../docs/api/languages.json)),
 * `speakers` (`auto|off|<n>`, `1 <= n <= 20`) and `maxSpeakers` (default 8, `1..20`). `build` validates in
 * contract order and reports the first rejection as an error code; the accepted extension allow-list is
 * checked case-insensitively, the content type is ignored.
 */
internal class UploadRequest(
    val filename: String?,
    val contentType: String?,
    val fields: Map<String, String>,
) {
    /** The created task, or the first contract violation with the error code of `docs/api/`'s Error table. */
    fun build(config: Config): Either {
        val language = fields["language"] ?: "auto"
        if (!LANGUAGES.contains(language)) {
            return Either.Rejected("invalid-language", "Language \"$language\" is not in docs/api/languages.json")
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
                    return Either.Rejected(
                        "invalid-speakers",
                        "speakers must be auto, off, or a number between 1 and $MAX_SPEAKERS, got \"$speakers\"",
                    )
                }
                speakersMode = SpeakersMode.FIXED
                speakersCount = count
            }
        }

        val rawMaxSpeakers = fields["maxSpeakers"]
        val maxSpeakers =
            when {
                rawMaxSpeakers == null -> 8
                else -> rawMaxSpeakers.toIntOrNull()
            }
        if (maxSpeakers == null || maxSpeakers !in 1..MAX_SPEAKERS) {
            return Either.Rejected(
                "invalid-max-speakers",
                "maxSpeakers must be a number between 1 and $MAX_SPEAKERS, got \"${rawMaxSpeakers ?: "(missing)"}\"",
            )
        }

        if (filename == null) {
            return Either.Rejected("missing-file", "No file part named \"file\" in the multipart body")
        }
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            return Either.Rejected(
                "unsupported-format",
                "\"$filename\" is not a supported audio or video file (allowed: $ALLOWED_EXTENSIONS_LIST)",
            )
        }

        return Either.Valid(
            Task(
                originalFilename = filename ?: "upload",
                contentType = contentType ?: "application/octet-stream",
                durationMs = null,
                status = TaskStatus.QUEUED,
                phase = null,
                // The route (UploadRoutes) assigns the end-of-queue position; the request has no queue context.
                queuePosition = null,
                language = language,
                speakersMode = speakersMode,
                speakersCount = speakersCount,
                maxSpeakers = maxSpeakers,
                model = modelName(config.whisperModel),
                progress = 0.0,
                diarizationStage = null,
                diarizationProgress = if (speakersMode == SpeakersMode.OFF) null else 0.0,
                error = null,
                createdAt = -1, // replaced by the route; the request carries no creation time of its own
                startedAt = null,
                finishedAt = null,
            ),
        )
    }

    /** A validated upload: the [task] to store, or the error [code]/[message] of the rejection. */
    sealed interface Either {
        data class Valid(
            val task: Task,
        ) : Either

        data class Rejected(
            val code: String,
            val message: String,
        ) : Either
    }
}

/**
 * The extensions the upload accepts, checked case-insensitively; the content type is ignored (item 12).
 * Packaged under `src/main/resources` in the contract file's order (parity: AllowedExtensionsContractSpec).
 */
internal val ALLOWED_EXTENSIONS: List<String> =
    Json
        .parseToJsonElement(
            Thread
                .currentThread()
                .contextClassLoader
                .getResource("allowed-extensions.json")!!
                .readText(),
        ).jsonArray
        .map { it.jsonPrimitive.content }

/** The allowed extensions, space-joined in the contract file's order, for the rejection message. */
private val ALLOWED_EXTENSIONS_LIST = ALLOWED_EXTENSIONS.joinToString(" ")

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
