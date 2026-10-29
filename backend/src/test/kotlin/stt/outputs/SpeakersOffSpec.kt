package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SpeakersOffSpec :
    BehaviorSpec({
        given("a task with speakers off: segments without a speakerId and no speakers") {
            `when`("its outputs are rendered") {
                then(
                    "txt is one trimmed segment per line and srt and vtt have one cue per segment without a speaker prefix",
                ) {
                    val segments =
                        listOf(
                            segment(speakerId = null, startMs = 0, endMs = 1_500, text = " Hello there."),
                            segment(speakerId = null, startMs = 1_500, endMs = 3_250, text = " again."),
                            segment(speakerId = null, startMs = 3_250, endMs = 4_000, text = " Fine."),
                        )

                    // Spec "Speakers off": the diarized
                    // shapes without labels, trimmed text (ADR 0030), one cue per segment.
                    renderTxt(segments, null) shouldBe "Hello there.\nagain.\nFine.\n"
                    renderSrt(segments, null) shouldBe
                        "1\n00:00:00,000 --> 00:00:01,500\nHello there.\n\n" +
                        "2\n00:00:01,500 --> 00:00:03,250\nagain.\n\n" +
                        "3\n00:00:03,250 --> 00:00:04,000\nFine.\n\n"
                    renderVtt(segments, null) shouldBe
                        "WEBVTT\n\n" +
                        "00:00:00.000 --> 00:00:01.500\nHello there.\n\n" +
                        "00:00:01.500 --> 00:00:03.250\nagain.\n\n" +
                        "00:00:03.250 --> 00:00:04.000\nFine.\n\n"
                }
            }
        }
        given("a task with speakers off whose segments include blank ones") {
            `when`("it is rendered as srt and vtt") {
                then("blank segments get no cue and srt numbers the remaining cues from 1 without gaps") {
                    val segments =
                        listOf(
                            segment(speakerId = null, startMs = 0, endMs = 1_000, text = " A."),
                            segment(speakerId = null, startMs = 1_000, endMs = 2_000, text = "  "),
                            segment(speakerId = null, startMs = 2_000, endMs = 3_000, text = " B."),
                        )
                    val allBlank = listOf(segment(speakerId = null, startMs = 0, endMs = 1_000, text = "  "))

                    // Decided 2026-10-09 (discovered item, option (b)): with speakers off, a segment whose trimmed text
                    // is empty gets no cue. A task whose segments are all blank still has segments, so vtt keeps its
                    // header (the no-speech case is zero segments).
                    renderSrt(segments, null) shouldBe
                        "1\n00:00:00,000 --> 00:00:01,000\nA.\n\n" +
                        "2\n00:00:02,000 --> 00:00:03,000\nB.\n\n"
                    renderVtt(segments, null) shouldBe
                        "WEBVTT\n\n" +
                        "00:00:00.000 --> 00:00:01.000\nA.\n\n" +
                        "00:00:02.000 --> 00:00:03.000\nB.\n\n"
                    renderSrt(allBlank, null) shouldBe ""
                    renderVtt(allBlank, null) shouldBe "WEBVTT\n\n"
                }
            }
        }
    })
