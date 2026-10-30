package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Item 74, test-list item "(discovered) whisper-cli's `result.language` is parsed as the detected
 * language, and `params.language` (the requested one) is never mistaken for it": whisper's JSON
 * echoes the requested language under `params` (here `de`, what the upload asked for) and the
 * language it actually detected under `result` (here `ja`); only the latter may become the task's
 * detected language, otherwise an `auto` upload would mark every transcript with the request.
 */
class ParseWhisperLanguageSpec :
    BehaviorSpec({
        given("whisper-cli JSON whose params language (the requested one) differs from the result language") {
            `when`("it is parsed") {
                then("the detected language is result.language, not params.language") {
                    val json =
                        """
                        {
                          "params": {"language": "de"},
                          "result": {"language": "ja"},
                          "transcription": [
                            {"offsets": {"from": 0, "to": 1000}, "text": "こんにちは"}
                          ]
                        }
                        """.trimIndent()

                    parseWhisperOutput(json).detectedLanguage shouldBe "ja"
                }
            }
        }
    })
