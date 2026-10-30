package stt.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.roundToLong

/**
 * Parses whisper-cli `-oj` output into its segments (port of oracle `parse_segments`) and the language whisper
 * detected (`result.language`; item 74 — `params.language` is only the requested one). One document is parsed
 * once for both.
 * whisper.cpp >= 1.7 writes a `transcription` array whose `offsets` are milliseconds; older versions
 * write a `segments` array with `start`/`end` in seconds (rounded to the millisecond here), which
 * takes precedence when present. Malformed output throws [IllegalArgumentException].
 */
fun parseWhisperOutput(json: String): TranscribedChunk = parseWhisperOutput(Json.parseToJsonElement(json).jsonObject)

/** The segments of whisper-cli `-oj` output (see [parseWhisperOutput]). */
fun parseSegments(json: String): List<WhisperSegment> = parseWhisperOutput(json).segments

private fun parseWhisperOutput(root: JsonObject): TranscribedChunk {
    val legacy = root["segments"]
    val segments =
        if (legacy != null) {
            legacy.jsonArray.map { parseLegacySegment(it.jsonObject) }
        } else {
            root["transcription"]?.jsonArray?.map { parseTranscriptionSegment(it.jsonObject) }.orEmpty()
        }
    val detectedLanguage =
        root["result"]
            ?.jsonObject
            ?.get("language")
            ?.jsonPrimitive
            ?.contentOrNull
    return TranscribedChunk(segments, detectedLanguage)
}

/** Defaults: missing `offsets`, `from` or `to` → 0; missing `text` → empty string. */
private fun parseTranscriptionSegment(segment: JsonObject): WhisperSegment {
    val offsets = segment["offsets"]?.jsonObject
    return WhisperSegment(
        startMs = offsets?.get("from")?.jsonPrimitive?.long ?: 0,
        endMs = offsets?.get("to")?.jsonPrimitive?.long ?: 0,
        text = segment["text"]?.jsonPrimitive?.content ?: "",
    )
}

/** No defaults, unlike [parseTranscriptionSegment]: legacy entries pass through and fail later on a missing key. */
private fun parseLegacySegment(segment: JsonObject): WhisperSegment =
    WhisperSegment(
        startMs = secondsToMs(segment.legacyField("start").double),
        endMs = secondsToMs(segment.legacyField("end").double),
        text = segment.legacyField("text").content,
    )

private fun JsonObject.legacyField(key: String): JsonPrimitive =
    requireNotNull(this[key]) { "a legacy segments entry lacks \"$key\"" }.jsonPrimitive

private fun secondsToMs(seconds: Double): Long = (seconds * 1000).roundToLong()
