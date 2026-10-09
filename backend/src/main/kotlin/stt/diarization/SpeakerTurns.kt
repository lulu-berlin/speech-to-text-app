package stt.diarization

import stt.db.Segment
import stt.db.Speaker

/**
 * Collapses consecutive [segments] with the same `speakerId` into one [SpeakerTurn] each (oracle `speaker_turns`),
 * labelled with that speaker's name from [speakers]. Turns merge by `speakerId`, not by name, so two speakers renamed
 * alike stay apart (ADR 0028). Every segment must have a speakerId that [speakers] contains.
 */
internal fun speakerTurns(
    segments: List<Segment>,
    speakers: List<Speaker>,
): List<SpeakerTurn> {
    val speakerOf = speakers.associate { it.speakerId to it.speaker }
    val turns = ArrayList<SpeakerTurn>()
    var start = 0
    while (start < segments.size) {
        val speakerId = requireNotNull(segments[start].speakerId) { "Segment ${segments[start]} has no speaker" }
        var end = start + 1
        while (end < segments.size && segments[end].speakerId == speakerId) end++
        val run = segments.subList(start, end)
        turns.add(
            SpeakerTurn(
                speakerId = speakerId,
                speaker = speakerOf.getValue(speakerId),
                startMs = run.first().startMs,
                endMs = run.last().endMs,
                // The oracle appends each trimmed text as f"{acc} {text}".strip(): the non-blank trimmed texts joined
                // with single spaces.
                text = run.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" "),
                segments = run.map { TurnSegment(startMs = it.startMs, endMs = it.endMs, text = it.text.trim()) },
            ),
        )
        start = end
    }
    return turns
}
