package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NoSpeechSpec :
    BehaviorSpec({
        given("a task with no segments") {
            `when`("its outputs are rendered") {
                then("txt, srt and vtt are empty") {
                    // Zero segments: the output files are touched, i.e. 0 bytes (vtt without
                    // its header).
                    renderTxt(emptyList(), emptyList()) shouldBe ""
                    renderSrt(emptyList(), emptyList()) shouldBe ""
                    renderVtt(emptyList(), emptyList()) shouldBe ""
                }
            }
        }
        given("a task with speakers off and no segments") {
            `when`("its outputs are rendered") {
                then("the outputs are the same no-speech bytes as with speakers on") {
                    // Decided 2026-10-09: no-speech output is identical with speakers on and off.
                    renderTxt(emptyList(), null) shouldBe ""
                    renderSrt(emptyList(), null) shouldBe ""
                    renderVtt(emptyList(), null) shouldBe ""
                }
            }
        }
    })
