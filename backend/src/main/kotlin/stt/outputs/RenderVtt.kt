package stt.outputs

import stt.db.Segment
import stt.db.Speaker
import stt.diarization.speakerTurns

/**
 * Renders a task's [segments] as vtt (oracle `write_diarized_outputs`): the `WEBVTT` header and a blank line, then
 * one cue per speaker turn from `HH:MM:SS.mmm` start to end, `Speaker N: {turn text}` (N = speakerId + 1), without cue
 * numbers, every cue ending with a blank line. Without segments, the file is empty (oracle `transcribe_one`).
 */
internal fun renderVtt(
    segments: List<Segment>,
    speakers: List<Speaker>,
): String {
    // Oracle `transcribe_one`, zero segments: the file is touched, so there is no WEBVTT header.
    if (segments.isEmpty()) return ""
    return buildString {
        append("WEBVTT\n\n")
        for (turn in speakerTurns(segments, speakers)) {
            append("${fmtVtt(turn.startMs)} --> ${fmtVtt(turn.endMs)}\n")
            append("Speaker ${turn.speakerId + 1}: ${turn.text}\n\n")
        }
    }
}
