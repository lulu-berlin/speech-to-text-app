package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/**
 * Renders a task's [segments] as json: keys `text` (the trimmed texts joined with single spaces) and `segments`, per
 * segment `start` and `end` in seconds and the trimmed `text` (ADR 0030). With [speakers] (the task's stored speakers;
 * oracle `write_diarized_outputs`) also `speakers` (the number of distinct speakerIds) after `text`, and the 0-based
 * `speaker` per segment; with `null` [speakers] (speakers off) neither. Python's `json.dump(..., ensure_ascii=False,
 * indent=2)` layout: two-space indentation, `": "` between key and value and no trailing newline. Without segments,
 * the compact no-speech document of `transcribe_one`.
 */
internal fun renderJson(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String {
    // Oracle `transcribe_one`, zero segments: the literal '{"text": "", "segments": []}\n' — compact, no "speakers".
    if (segments.isEmpty()) return "{\"text\": \"\", \"segments\": []}\n"
    return buildString {
        append("{\n")
        append("  \"text\": ${jsonString(segments.map { it.text.trim() }.joinToString(" ").trim())},\n")
        if (speakers != null) append("  \"speakers\": ${segments.map(::speakerIdOf).distinct().size},\n")
        append("  \"segments\": [\n")
        segments.forEachIndexed { index, segment ->
            append("    {\n")
            append("      \"start\": ${seconds(segment.startMs)},\n")
            append("      \"end\": ${seconds(segment.endMs)},\n")
            append("      \"text\": ${jsonString(segment.text.trim())}")
            if (speakers != null) append(",\n      \"speaker\": ${speakerIdOf(segment)}")
            append("\n    }")
            append(if (index == segments.lastIndex) "\n" else ",\n")
        }
        append("  ]\n}")
    }
}

/** Seconds as Python's `repr(float)` prints them; JDK 21's shortest round-trip matches for segment times. */
private fun seconds(ms: Long): String = (ms / 1000.0).toString()

/**
 * A json string as Python's encoder writes it with `ensure_ascii=False`: `"` and `\` are escaped, `\b \f \n \r \t`
 * use their short escapes, every other character below U+0020 becomes `\u00xx` (lower-case hex), and everything else,
 * non-ASCII included, is written as-is.
 */
private fun jsonString(text: String): String =
    buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }

private fun speakerIdOf(segment: Segment): Int = requireNotNull(segment.speakerId) { "Segment $segment has no speaker" }
