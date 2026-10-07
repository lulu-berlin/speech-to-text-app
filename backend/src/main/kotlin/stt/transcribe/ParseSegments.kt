package stt.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Parses whisper-cli `-oj` output into segments (port of oracle `parse_segments`).
 * whisper.cpp >= 1.7 writes a `transcription` array whose `offsets` are milliseconds.
 */
fun parseSegments(json: String): List<WhisperSegment> =
    Json.parseToJsonElement(json).jsonObject.getValue("transcription").jsonArray.map { element ->
        val segment = element.jsonObject
        val offsets = segment.getValue("offsets").jsonObject
        WhisperSegment(
            startMs = offsets.getValue("from").jsonPrimitive.long,
            endMs = offsets.getValue("to").jsonPrimitive.long,
            text = segment.getValue("text").jsonPrimitive.content,
        )
    }
