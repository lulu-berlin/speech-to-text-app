package stt.outputs

import stt.db.Segment

/**
 * Renders a task's [segments] as json (oracle `write_diarized_outputs`): keys `text`, `speakers` (the number of
 * distinct speakerIds) and `segments`; per segment `start` and `end` in seconds, the trimmed `text` (ADR 0030) and the
 * 0-based `speaker`. Python's `json.dump(..., ensure_ascii=False, indent=2)` layout: two-space indentation, `": "`
 * between key and value, an empty list as `[]`, no trailing newline.
 */
internal fun renderJson(segments: List<Segment>): String =
    buildString {
        append("{\n")
        append("  \"text\": ${jsonString(segments.map { it.text.trim() }.joinToString(" ").trim())},\n")
        append("  \"speakers\": ${segments.map(::speakerIdOf).distinct().size},\n")
        append("  \"segments\": ")
        if (segments.isEmpty()) {
            append("[]")
        } else {
            append("[\n")
            segments.forEachIndexed { index, segment ->
                append("    {\n")
                append("      \"start\": ${seconds(segment.startMs)},\n")
                append("      \"end\": ${seconds(segment.endMs)},\n")
                append("      \"text\": ${jsonString(segment.text.trim())},\n")
                append("      \"speaker\": ${speakerIdOf(segment)}\n")
                append("    }")
                append(if (index == segments.lastIndex) "\n" else ",\n")
            }
            append("  ]")
        }
        append("\n}")
    }

/** Seconds as Python's `repr(float)` prints them; JDK 21's shortest round-trip matches for segment times. */
private fun seconds(ms: Long): String = (ms / 1000.0).toString()

/** A json string. Escaping (quotes, backslashes, control characters) is not implemented yet. */
private fun jsonString(text: String): String = "\"$text\""

private fun speakerIdOf(segment: Segment): Int = requireNotNull(segment.speakerId) { "Segment $segment has no speaker" }
