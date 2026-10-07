package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ParseSegmentsSpec :
    BehaviorSpec({
        given("whisper-cli JSON with a transcription array") {
            `when`("it is parsed") {
                then("it yields segments with millisecond offsets and text") {
                    // Shape as written by whisper-cli 1.9.2 -oj for samples/jfk.wav with ggml-tiny.en (fields trimmed).
                    val json =
                        """
                        {
                          "systeminfo": "AVX = 1 | AVX2 = 1",
                          "model": {"type": "tiny"},
                          "result": {"language": "en"},
                          "transcription": [
                            {
                              "timestamps": {"from": "00:00:00,000", "to": "00:00:07,940"},
                              "offsets": {"from": 0, "to": 7940},
                              "text": " And so my fellow Americans ask not what your country can do for you"
                            },
                            {
                              "timestamps": {"from": "00:00:07,940", "to": "00:00:10,760"},
                              "offsets": {"from": 7940, "to": 10760},
                              "text": " ask what you can do for your country."
                            }
                          ]
                        }
                        """.trimIndent()

                    parseSegments(json).shouldBe(
                        listOf(
                            WhisperSegment(
                                startMs = 0,
                                endMs = 7_940,
                                text = " And so my fellow Americans ask not what your country can do for you",
                            ),
                            WhisperSegment(startMs = 7_940, endMs = 10_760, text = " ask what you can do for your country."),
                        ),
                    )
                }
            }
        }
    })
