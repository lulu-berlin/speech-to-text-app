package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment

class PromptTailSpec :
    BehaviorSpec({
        given("the previous chunk's segments") {
            `when`("the prompt is built from them") {
                then("it is the previous chunk's last ~200 characters, cut at a word boundary") {
                    // 19 words of 9 letters joined by single spaces: 19 × 9 + 18 = 189 characters.
                    val words = List(19) { "abcdefghi" }.joinToString(" ")

                    // Short text: whole, trimmed.
                    promptTail(segments(" And so my fellow Americans", " ask not")).shouldBe("And so my fellow Americans ask not")

                    // "the inauguration " + words = 4 + 12 + 1 + 189 = 206 characters; the last 200 start at index 6,
                    // inside "inauguration" ("auguration"), so that partial word is dropped.
                    promptTail(segments(" the inauguration", " $words")).shouldBe(words)

                    // "the Washington " + words = 4 + 10 + 1 + 189 = 204 characters; the last 200 start at index 4,
                    // the first letter of "Washington" (after a space), so the whole word is kept: 10 + 1 + 189 = 200.
                    promptTail(segments(" the Washington", " $words")).shouldBe("Washington $words")

                    // A silent previous chunk: no prompt.
                    promptTail(emptyList()).shouldBe(null)
                }
            }
        }
    })

private fun segments(vararg texts: String): List<Segment> =
    texts.mapIndexed { seq, text -> Segment(chunkIdx = 0, seq = seq, startMs = 0, endMs = 0, text = text, speaker = null) }
