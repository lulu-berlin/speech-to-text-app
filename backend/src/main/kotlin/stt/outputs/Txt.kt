package stt.outputs

import stt.db.Segment

/**
 * Renders a task's [segments] as txt (oracle `write_diarized_outputs`): segment-level, a `[speaker N]` line
 * (N = speakerId + 1) at every speaker change, blocks separated by one blank line, each trimmed text on its own line
 * (ADR 0030).
 */
internal fun renderTxt(segments: List<Segment>): String =
    buildString {
        var previous: Int? = null
        for (segment in segments) {
            val speakerId = requireNotNull(segment.speakerId) { "Segment $segment has no speaker" }
            if (speakerId != previous) {
                if (previous != null) append('\n')
                append("[speaker ${speakerId + 1}]\n")
                previous = speakerId
            }
            append(segment.text.trim()).append('\n')
        }
    }
