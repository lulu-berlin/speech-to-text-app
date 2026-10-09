package stt.outputs

import stt.db.Segment
import stt.db.Speaker
import stt.diarization.speakerTurns

/**
 * Renders a task's [segments] as srt: numbered cues from `HH:MM:SS,mmm` start to end, every cue ending with a blank
 * line. With [speakers] (the task's stored speakers; oracle `write_diarized_outputs`): one cue per speaker turn,
 * `Speaker N: {turn text}` (N = speakerId + 1). With `null` [speakers] (speakers off): one cue per segment, its trimmed
 * text without a label.
 */
internal fun renderSrt(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): String =
    buildString {
        if (speakers == null) {
            segments.forEachIndexed { index, segment ->
                append("${index + 1}\n")
                append("${fmtSrt(segment.startMs)} --> ${fmtSrt(segment.endMs)}\n")
                append("${segment.text.trim()}\n\n")
            }
        } else {
            speakerTurns(segments, speakers).forEachIndexed { index, turn ->
                append("${index + 1}\n")
                append("${fmtSrt(turn.startMs)} --> ${fmtSrt(turn.endMs)}\n")
                append("Speaker ${turn.speakerId + 1}: ${turn.text}\n\n")
            }
        }
    }
