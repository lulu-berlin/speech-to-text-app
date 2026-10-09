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
    })
