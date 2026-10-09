package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment
import stt.db.Speaker

class SpeakerTurnsSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 0, 1, 0, one of them blank, with untrimmed texts") {
            `when`("they are collapsed into speaker turns") {
                then(
                    "consecutive same-speaker segments form one turn that keeps its segments with trimmed texts; a speaker change starts a new turn",
                ) {
                    val speakers = listOf(Speaker(speakerId = 0, speaker = "Speaker 1"), Speaker(speakerId = 1, speaker = "Speaker 2"))
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_000, text = " Hello "),
                            segment(speakerId = 0, startMs = 1_000, endMs = 2_000, text = "  "),
                            segment(speakerId = 0, startMs = 2_000, endMs = 3_000, text = "there."),
                            segment(speakerId = 1, startMs = 3_000, endMs = 4_000, text = " Hi."),
                            segment(speakerId = 0, startMs = 4_000, endMs = 5_000, text = " Bye."),
                        )

                    // Oracle `speaker_turns`: each text trimmed, appended as f"{acc} {text}".strip(), so the blank segment
                    // adds no space: "Hello" -> "Hello" -> "Hello there.". The kept segments' texts are trimmed too (ADR 0030).
                    speakerTurns(segments, speakers).shouldBe(
                        listOf(
                            SpeakerTurn(
                                speakerId = 0,
                                speaker = "Speaker 1",
                                startMs = 0,
                                endMs = 3_000,
                                text = "Hello there.",
                                segments =
                                    listOf(
                                        TurnSegment(startMs = 0, endMs = 1_000, text = "Hello"),
                                        TurnSegment(startMs = 1_000, endMs = 2_000, text = ""),
                                        TurnSegment(startMs = 2_000, endMs = 3_000, text = "there."),
                                    ),
                            ),
                            SpeakerTurn(
                                speakerId = 1,
                                speaker = "Speaker 2",
                                startMs = 3_000,
                                endMs = 4_000,
                                text = "Hi.",
                                segments = listOf(TurnSegment(startMs = 3_000, endMs = 4_000, text = "Hi.")),
                            ),
                            SpeakerTurn(
                                speakerId = 0,
                                speaker = "Speaker 1",
                                startMs = 4_000,
                                endMs = 5_000,
                                text = "Bye.",
                                segments = listOf(TurnSegment(startMs = 4_000, endMs = 5_000, text = "Bye.")),
                            ),
                        ),
                    )
                }
            }
        }
    })

private fun segment(
    speakerId: Int,
    startMs: Long,
    endMs: Long,
    text: String,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = text, speakerId = speakerId)
