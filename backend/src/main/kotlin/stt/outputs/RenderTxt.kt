package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/**
 * Renders a task's [segments] as txt, each trimmed text on its own line (ADR 0030). With [speakers] (the task's stored
 * speakers; oracle `write_diarized_outputs`): segment-level, a `[speaker N]` line (N = speakerId + 1, never the stored
 * name) at every speaker change, blocks separated by one blank line. With `null` [speakers] (speakers off): no labels.
 */
internal fun renderTxt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String =
    buildString {
        var previous: Int? = null
        for (segment in segments) {
            if (speakers != null) {
                val speakerId = requireNotNull(segment.speakerId) { "Segment $segment has no speaker" }
                if (speakerId != previous) {
                    if (previous != null) append('\n')
                    append("[speaker ${speakerId + 1}]\n")
                    previous = speakerId
                }
            }
            append(segment.text.trim()).append('\n')
        }
    }
