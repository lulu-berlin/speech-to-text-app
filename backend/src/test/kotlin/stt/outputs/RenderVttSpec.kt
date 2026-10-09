package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RenderVttSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 1 with untrimmed texts") {
            `when`("they are rendered as vtt") {
                then("the file starts with WEBVTT and a blank line and contains the same turns without cue numbers") {
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_500, text = " Hello there."),
                            segment(speakerId = 0, startMs = 1_500, endMs = 3_250, text = " again."),
                            segment(speakerId = 1, startMs = 3_250, endMs = 4_000, text = " Fine."),
                        )

                    // Oracle `write_diarized_outputs`, vtt: f"WEBVTT\n\n" first, then per turn
                    // f"{fmt_vtt(start)} --> {fmt_vtt(end)}\n{name}: {text}\n\n" with no cue number.
                    // fmt_vtt is fmt_srt with "." before the milliseconds.
                    renderVtt(segments, speakers1And2) shouldBe
                        "WEBVTT\n\n" +
                        "00:00:00.000 --> 00:00:03.250\nSpeaker 1: Hello there. again.\n\n" +
                        "00:00:03.250 --> 00:00:04.000\nSpeaker 2: Fine.\n\n"
                }
            }
        }
    })
