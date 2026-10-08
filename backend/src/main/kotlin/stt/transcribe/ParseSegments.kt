package stt.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.roundToLong

/**
 * Parses whisper-cli `-oj` output into segments (port of oracle `parse_segments`).
 * whisper.cpp >= 1.7 writes a `transcription` array whose `offsets` are milliseconds; older versions
 * write a `segments` array with `start`/`end` in seconds (rounded to the millisecond here), which
 * takes precedence when present. Malformed output throws [IllegalArgumentException].
 */
fun parseSegments(json: String): List<WhisperSegment> {
    val root = Json.parseToJsonElement(json).jsonObject
    val legacy = root["segments"]
    if (legacy != null) return legacy.jsonArray.map { parseLegacySegment(it.jsonObject) }
    val transcription = root["transcription"] ?: return emptyList()
    return transcription.jsonArray.map { parseTranscriptionSegment(it.jsonObject) }
}

/** Oracle defaults: missing `offsets`, `from` or `to` → 0; missing `text` → empty string. */
private fun parseTranscriptionSegment(segment: JsonObject): WhisperSegment {
    val offsets = segment["offsets"]?.jsonObject
    return WhisperSegment(
        startMs = offsets?.get("from")?.jsonPrimitive?.long ?: 0,
        endMs = offsets?.get("to")?.jsonPrimitive?.long ?: 0,
        text = segment["text"]?.jsonPrimitive?.content ?: "",
    )
}

/** No defaults, unlike [parseTranscriptionSegment]: the oracle passes legacy entries through and fails later on a missing key. */
private fun parseLegacySegment(segment: JsonObject): WhisperSegment =
    WhisperSegment(
        startMs = secondsToMs(segment.legacyField("start").double),
        endMs = secondsToMs(segment.legacyField("end").double),
        text = segment.legacyField("text").content,
    )

private fun JsonObject.legacyField(key: String): JsonPrimitive =
    requireNotNull(this[key]) { "a legacy segments entry lacks \"$key\"" }.jsonPrimitive

private fun secondsToMs(seconds: Double): Long = (seconds * 1000).roundToLong()
