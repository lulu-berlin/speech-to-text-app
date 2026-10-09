package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RenderJsonSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 1 with untrimmed texts") {
            `when`("they are rendered as json") {
                then(
                    "text, speakers and segments are in that order with 2-space indentation, no trailing newline, float seconds, trimmed texts and 0-based speakers",
                ) {
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_500, text = " Hello there."),
                            segment(speakerId = 0, startMs = 1_500, endMs = 3_250, text = " again."),
                            segment(speakerId = 1, startMs = 3_250, endMs = 4_000, text = " Fine."),
                        )

                    // Oracle `write_diarized_outputs`, json: json.dump({"text": …, "speakers": len(set(speakers)),
                    // "segments": [{"start", "end", "text", "speaker"}, …]}, ensure_ascii=False, indent=2) — no
                    // trailing newline, ": " and "," separators, nested objects on their own lines.
                    // text = " ".join(seg["text"].strip() for seg in segments).strip() = "Hello there. again. Fine.";
                    // start/end are ms / 1000.0 printed like Python repr(float) (0.0, 1.5, 3.25, 4.0);
                    // speaker is the 0-based index; every segments[].text is trimmed (ADR 0030).
                    renderJson(segments) shouldBe
                        """
                        {
                          "text": "Hello there. again. Fine.",
                          "speakers": 2,
                          "segments": [
                            {
                              "start": 0.0,
                              "end": 1.5,
                              "text": "Hello there.",
                              "speaker": 0
                            },
                            {
                              "start": 1.5,
                              "end": 3.25,
                              "text": "again.",
                              "speaker": 0
                            },
                            {
                              "start": 3.25,
                              "end": 4.0,
                              "text": "Fine.",
                              "speaker": 1
                            }
                          ]
                        }
                        """.trimIndent()
                }
            }
        }
        given("segments of one speaker whose texts include a blank one") {
            `when`("they are rendered as json") {
                then("text is the plain join of every trimmed text, so the blank segment leaves two adjacent spaces") {
                    val segments =
                        listOf(
                            segment(speakerId = 0, startMs = 0, endMs = 1_000, text = " A "),
                            segment(speakerId = 0, startMs = 1_000, endMs = 2_000, text = "  "),
                            segment(speakerId = 0, startMs = 2_000, endMs = 3_000, text = " B "),
                        )

                    // Oracle: full_text = " ".join(seg["text"].strip() for seg in segments).strip() joins every
                    // trimmed text, including a blank one: "A" + " " + "" + " " + "B" -> "A  B" (unlike speaker_turns,
                    // whose f"{acc} {text}".strip() fold drops blanks).
                    renderJson(segments) shouldBe
                        """
                        {
                          "text": "A  B",
                          "speakers": 1,
                          "segments": [
                            {
                              "start": 0.0,
                              "end": 1.0,
                              "text": "A",
                              "speaker": 0
                            },
                            {
                              "start": 1.0,
                              "end": 2.0,
                              "text": "",
                              "speaker": 0
                            },
                            {
                              "start": 2.0,
                              "end": 3.0,
                              "text": "B",
                              "speaker": 0
                            }
                          ]
                        }
                        """.trimIndent()
                }
            }
        }
        given("a segment whose text contains quotes, a backslash, control characters and non-ASCII") {
            `when`("it is rendered as json") {
                then("non-ASCII stays unescaped and quotes, backslashes and control characters are escaped like Python") {
                    val segments =
                        listOf(
                            segment(
                                speakerId = 0,
                                startMs = 0,
                                endMs = 1_000,
                                text = " A \"quoted\" back\\slash\nnewline\ttab\u0001ctrl ü 日 ",
                            ),
                        )

                    // Python json with ensure_ascii=False (encode_basestring): ESCAPE_DCT maps '"' -> \" and '\' -> \\
                    // and \b \f \n \r \t to their short escapes; every other character below U+0020 becomes
                    // \u00xx (lower-case hex); everything else, including non-ASCII, is written as-is.
                    // The trimmed text is: A "quoted" back\slash<LF>newline<TAB>tab<U+0001>ctrl ü 日
                    renderJson(segments) shouldBe
                        """
                        {
                          "text": "A \"quoted\" back\\slash\nnewline\ttab\u0001ctrl ü 日",
                          "speakers": 1,
                          "segments": [
                            {
                              "start": 0.0,
                              "end": 1.0,
                              "text": "A \"quoted\" back\\slash\nnewline\ttab\u0001ctrl ü 日",
                              "speaker": 0
                            }
                          ]
                        }
                        """.trimIndent()
                }
            }
        }
    })
