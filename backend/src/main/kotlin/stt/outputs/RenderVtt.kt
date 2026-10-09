package stt.outputs

import stt.db.Segment
import stt.db.Speaker
import stt.diarization.speakerTurns

/**
 * Renders a task's [segments] as vtt: the `WEBVTT` header and a blank line, then cues from `HH:MM:SS.mmm` start to end
 * without cue numbers, every cue ending with a blank line. With [speakers] (the task's stored speakers; oracle
 * `write_diarized_outputs`): one cue per speaker turn, `Speaker N: {turn text}` (N = speakerId + 1). With `null`
 * [speakers] (speakers off): one cue per segment, its trimmed text without a label. Without segments, the file is empty
 * (oracle `transcribe_one`).
 */
internal fun renderVtt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String {
    // Oracle `transcribe_one`, zero segments: the file is touched, so there is no WEBVTT header.
    if (segments.isEmpty()) return ""
    return buildString {
        append("WEBVTT\n\n")
        if (speakers == null) {
            for (segment in segments) {
                append("${fmtVtt(segment.startMs)} --> ${fmtVtt(segment.endMs)}\n")
                append("${segment.text.trim()}\n\n")
            }
        } else {
            for (turn in speakerTurns(segments, speakers)) {
                append("${fmtVtt(turn.startMs)} --> ${fmtVtt(turn.endMs)}\n")
                append("Speaker ${turn.speakerId + 1}: ${turn.text}\n\n")
            }
        }
    }
}
