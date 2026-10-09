package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Speaker

class SpeakerLabelsSpec :
    BehaviorSpec({
        given("a task whose speakers 0 and 1 are stored as Alice and Bob") {
            `when`("its outputs are rendered") {
                then("the labels are the stored names in txt headers and srt/vtt cues") {
                    val speakers = listOf(Speaker(speakerId = 0, speaker = "Alice"), Speaker(speakerId = 1, speaker = "Bob"))
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_000, text = " Hi."),
                            segment(speakerId = 1, startMs = 1_000, endMs = 2_000, text = " Hello."),
                        )

                    // ADR 0034: txt block header "[{name}]", srt/vtt cue text "{name}: {turn text}", name = the stored
                    // Speaker.speaker; layouts as in the oracle's write_diarized_outputs.
                    renderTxt(segments, speakers) shouldBe "[Alice]\nHi.\n\n[Bob]\nHello.\n"
                    renderSrt(segments, speakers) shouldBe
                        "1\n00:00:00,000 --> 00:00:01,000\nAlice: Hi.\n\n" +
                        "2\n00:00:01,000 --> 00:00:02,000\nBob: Hello.\n\n"
                    renderVtt(segments, speakers) shouldBe
                        "WEBVTT\n\n" +
                        "00:00:00.000 --> 00:00:01.000\nAlice: Hi.\n\n" +
                        "00:00:01.000 --> 00:00:02.000\nBob: Hello.\n\n"
                }
            }
        }
    })
