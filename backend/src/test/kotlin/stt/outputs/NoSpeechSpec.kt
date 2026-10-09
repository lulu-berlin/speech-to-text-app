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
    })
