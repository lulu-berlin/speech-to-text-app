package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RenderTxtSpec :
    BehaviorSpec({
        given("segments of speakers 0, 0, 1, 0 with untrimmed texts") {
            `when`("they are rendered as txt") {
                then(
                    "a [{name}] block starts at every speaker change, blocks are separated by one blank line, and each trimmed segment is on its own line",
                ) {
                    val segments =
                        listOf(
                            segment(speakerId = 0, text = " Hello there."),
                            segment(speakerId = 0, text = "How are you? "),
                            segment(speakerId = 1, text = " Fine."),
                            segment(speakerId = 0, text = " Bye."),
                        )

                    // Oracle `write_diarized_outputs`, txt: on a speaker change "\n" (except before the first block),
                    // then the block header, here "[{stored name}]\n" (ADR 0034; the oracle prints f"[speaker {spk + 1}]\n");
                    // every segment f"{seg['text'].strip()}\n". The stored names are "Speaker 1" and "Speaker 2".
                    renderTxt(segments, speakers(0, 1)) shouldBe
                        "[Speaker 1]\nHello there.\nHow are you?\n\n[Speaker 2]\nFine.\n\n[Speaker 1]\nBye.\n"
                }
            }
        }
    })
