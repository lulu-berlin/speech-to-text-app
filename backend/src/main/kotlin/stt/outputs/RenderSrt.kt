package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/**
 * Renders a task's [segments] as srt: the [cues] (per speaker turn with [speakers], per segment with `null` speakers),
 * numbered from 1, each from `HH:MM:SS,mmm` start to end and ending with a blank line.
 */
internal fun renderSrt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String =
    buildString {
        cues(segments, speakers).forEachIndexed { index, cue ->
            append("${index + 1}\n")
            append("${fmtSrt(cue.startMs)} --> ${fmtSrt(cue.endMs)}\n")
            append("${cue.text}\n\n")
        }
    }
