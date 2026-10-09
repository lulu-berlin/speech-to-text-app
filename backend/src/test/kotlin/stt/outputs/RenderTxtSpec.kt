package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment

class RenderTxtSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 1, 0 with untrimmed texts") {
            `when`("they are rendered as txt") {
                then(
                    "a [speaker N] block starts at every speaker change, blocks are separated by one blank line, and each trimmed segment is on its own line",
                ) {
                    val segments =
                        listOf(
                            segment(speakerId = 0, text = " Hello there."),
                            segment(speakerId = 0, text = "How are you? "),
                            segment(speakerId = 1, text = " Fine."),
                            segment(speakerId = 0, text = " Bye."),
                        )

                    // Oracle `write_diarized_outputs`, txt: on a speaker change "\n" (except before the first block),
                    // then f"[speaker {spk + 1}]\n"; every segment f"{seg['text'].strip()}\n".
                    renderTxt(segments) shouldBe
                        "[speaker 1]\nHello there.\nHow are you?\n\n[speaker 2]\nFine.\n\n[speaker 1]\nBye.\n"
                }
            }
        }
    })

private fun segment(
    speakerId: Int,
    text: String,
) = Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 0, text = text, speakerId = speakerId)
