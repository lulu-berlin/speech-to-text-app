package stt.outputs

import stt.db.Segment
import stt.db.Speaker
import stt.diarization.speakerTurns

/**
 * The srt/vtt cues of a task's [segments]. With [speakers] (the task's stored speakers; oracle
 * `write_diarized_outputs`): one cue per speaker turn, `Speaker N: {turn text}` (N = speakerId + 1, never the stored
 * name). With `null` [speakers] (speakers off): one cue per segment with its trimmed text, without a label.
 */
internal fun cues(
    segments: List<Segment>,
    speakers: List<Speaker>?,
): List<Cue> =
    if (speakers == null) {
        segments.map { Cue(it.startMs, it.endMs, it.text.trim()) }
    } else {
        speakerTurns(segments, speakers).map { Cue(it.startMs, it.endMs, "Speaker ${it.speakerId + 1}: ${it.text}") }
    }
