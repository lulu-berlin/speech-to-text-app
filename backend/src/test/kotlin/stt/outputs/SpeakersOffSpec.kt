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

                    // Spec "Speakers off" (no oracle code: the oracle delegates to whisper-cli's writers): the diarized
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
    })
