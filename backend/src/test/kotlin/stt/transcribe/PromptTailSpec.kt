package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment

/** 19 words of 9 letters joined by single spaces: 19 × 9 + 18 = 189 characters. */
private val NINETEEN_WORDS = List(19) { "abcdefghi" }.joinToString(" ")

class PromptTailSpec :
    BehaviorSpec({
        given("the previous chunk's segments") {
            `when`("the prompt is built from them") {
                then("it is the previous chunk's last ~200 characters, cut at a word boundary") {
                    // Short text: whole, trimmed.
                    promptTail(segments(" And so my fellow Americans", " ask not")).shouldBe("And so my fellow Americans ask not")

                    // "the inauguration " + NINETEEN_WORDS = 4 + 12 + 1 + 189 = 206 characters; the last 200 start at index 6,
                    // inside "inauguration" ("auguration"), so that partial word is dropped.
                    promptTail(segments(" the inauguration", " $NINETEEN_WORDS")).shouldBe(NINETEEN_WORDS)

                    // "the Washington " + NINETEEN_WORDS = 4 + 10 + 1 + 189 = 204 characters; the last 200 start at index 4,
                    // the first letter of "Washington" (after a space), so the whole word is kept: 10 + 1 + 189 = 200.
                    promptTail(segments(" the Washington", " $NINETEEN_WORDS")).shouldBe("Washington $NINETEEN_WORDS")

                    // A silent previous chunk: no prompt.
                    promptTail(emptyList()).shouldBe(null)
                }
            }
        }

        given("previous-chunk text whose cut falls in a word followed by a tab or a newline") {
            `when`("the prompt is built from it") {
                then("the partial word is dropped up to that whitespace character, keeping the next whole word") {
                    // "the inauguration\t" + NINETEEN_WORDS = 4 + 12 + 1 + 189 = 206 characters; the last 200 start at index 6,
                    // inside "inauguration" ("auguration"); only that partial word and the tab go, all 19 words stay.
                    promptTail(segments(" the inauguration", "\t$NINETEEN_WORDS")).shouldBe(NINETEEN_WORDS)
                    // The same with a newline after the partial word.
                    promptTail(segments(" the inauguration", "\n$NINETEEN_WORDS")).shouldBe(NINETEEN_WORDS)
                }
            }
        }
    })

private fun segments(vararg texts: String): List<Segment> =
    texts.mapIndexed { seq, text -> Segment(chunkIdx = 0, seq = seq, startMs = 0, endMs = 0, text = text, speaker = null) }
