package stt.outputs

import stt.db.Segment
import stt.db.Speaker

/**
 * Renders a task's [segments] as vtt: the `WEBVTT` header and a blank line, then the [cues] (per speaker turn with
 * [speakers], per segment with `null` speakers) without numbers, each from `HH:MM:SS.mmm` start to end and ending with
 * a blank line. Without segments, the file is empty (oracle `transcribe_one`).
 */
internal fun renderVtt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String {
    // Oracle `transcribe_one`, zero segments: the file is touched, so there is no WEBVTT header.
    if (segments.isEmpty()) return ""
    return buildString {
        append("WEBVTT\n\n")
        for (cue in cues(segments, speakers)) {
            append("${fmtVtt(cue.startMs)} --> ${fmtVtt(cue.endMs)}\n")
            append("${cue.text}\n\n")
        }
    }
}
