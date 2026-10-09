package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Speaker

class RenderSrtSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 1 with untrimmed texts") {
            `when`("they are rendered as srt") {
                then("consecutive same-speaker segments form one numbered cue \"Speaker N: text\" with first start and last end") {
                    val speakers = listOf(Speaker(speakerId = 0, speaker = "Speaker 1"), Speaker(speakerId = 1, speaker = "Speaker 2"))
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_500, text = " Hello there."),
                            segment(speakerId = 0, startMs = 1_500, endMs = 3_250, text = " again."),
                            segment(speakerId = 1, startMs = 3_250, endMs = 4_000, text = " Fine."),
                        )

                    // Oracle `write_diarized_outputs`, srt: per turn f"{i}\n{fmt_srt(start)} --> {fmt_srt(end)}\n{name}: {text}\n\n";
                    // speaker_turns merges segments 0 and 1 (same speaker) into one turn, texts joined with single spaces.
                    // fmt_srt: ms = int(round(t*1000)), divmod by 3_600_000/60_000/1000, f"{h:02d}:{m:02d}:{s:02d},{ms:03d}".
                    renderSrt(segments, speakers) shouldBe
                        "1\n00:00:00,000 --> 00:00:03,250\nSpeaker 1: Hello there. again.\n\n" +
                        "2\n00:00:03,250 --> 00:00:04,000\nSpeaker 2: Fine.\n\n"
                }
            }
        }
    })
