package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NoSpeechSpec :
    BehaviorSpec({
        given("a task with no segments") {
            `when`("its outputs are rendered") {
                then("json is the compact no-speech document with a trailing newline and txt, srt and vtt are empty") {
                    // Oracle `transcribe_one`, zero segments: json is the literal '{"text": "", "segments": []}\n'
                    // (compact, no "speakers" key, trailing newline) and the other formats are touched, i.e. 0 bytes
                    // (vtt without its header).
                    renderTxt(emptyList(), emptyList()) shouldBe ""
                    renderSrt(emptyList(), emptyList()) shouldBe ""
                    renderVtt(emptyList(), emptyList()) shouldBe ""
                    renderJson(emptyList(), emptyList()) shouldBe "{\"text\": \"\", \"segments\": []}\n"
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
                    renderJson(emptyList(), null) shouldBe "{\"text\": \"\", \"segments\": []}\n"
                }
            }
        }
    })
