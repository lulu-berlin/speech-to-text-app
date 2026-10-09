package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/**
 * Renders a task's [segments] as txt, each trimmed text on its own line (ADR 0030). With [speakers] (the task's stored
 * speakers; oracle `write_diarized_outputs`): segment-level, a `[{name}]` line with the speaker's stored name
 * (ADR 0034) at every speaker change, blocks separated by one blank line. With `null` [speakers] (speakers off): no
 * speaker lines.
 */
internal fun renderTxt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String =
    buildString {
        val nameOf = speakers?.associate { it.speakerId to it.speaker }
        var previous: Int? = null
        for (segment in segments) {
            if (nameOf != null) {
                val speakerId = requireNotNull(segment.speakerId) { "Segment $segment has no speaker" }
                if (speakerId != previous) {
                    if (previous != null) append('\n')
                    append("[${nameOf.getValue(speakerId)}]\n")
                    previous = speakerId
                }
            }
            append(segment.text.trim()).append('\n')
        }
    }
