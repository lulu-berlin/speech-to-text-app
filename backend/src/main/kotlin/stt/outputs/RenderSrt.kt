package stt.outputs

import stt.db.Segment
import stt.db.Speaker
import stt.diarization.speakerTurns

/**
 * Renders a task's [segments] as srt (oracle `write_diarized_outputs`): one numbered cue per speaker turn from
 * `HH:MM:SS,mmm` start to end, `Speaker N: {turn text}` (N = speakerId + 1), every cue ending with a blank line.
 */
internal fun renderSrt(
    segments: List<Segment>,
    speakers: List<Speaker>,
): String =
    buildString {
        speakerTurns(segments, speakers).forEachIndexed { index, turn ->
            append("${index + 1}\n")
            append("${fmtSrt(turn.startMs)} --> ${fmtSrt(turn.endMs)}\n")
            append("Speaker ${turn.speakerId + 1}: ${turn.text}\n\n")
        }
    }
