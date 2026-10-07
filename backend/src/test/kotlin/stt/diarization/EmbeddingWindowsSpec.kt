package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EmbeddingWindowsSpec :
    BehaviorSpec({
        given("a 3 s PCM and a segment of 1 s") {
            `when`("building the embedding window") {
                then("the window is 2 s with the segment's samples centered at offset 12000 and zeros elsewhere") {
                    // pcm[i] = i; the segment [500 ms, 1000 ms] is samples [8000, 16000) with values 8000..15999.
                    val pcm = FloatArray(48_000) { i -> i.toFloat() }

                    val window = embeddingWindow(pcm, startMs = 500, endMs = 1000)

                    // dur = (1000 - 500) * 16 = 8000 samples; offset = (32000 - 8000) / 2 = 12000.
                    window.size.shouldBe(32_000)
                    window[0].shouldBe(0f)
                    window[11999].shouldBe(0f)
                    window[12000].shouldBe(8000f)
                    window[12001].shouldBe(8001f)
                    window[19999].shouldBe(15999f)
                    window[20000].shouldBe(0f)
                    window[31999].shouldBe(0f)
                }
            }
        }
        given("a 30 s PCM and segments longer than 20 s") {
            `when`("building the embedding window") {
                then("each becomes the centered 20 s slice") {
                    val pcm = FloatArray(480_000) { i -> i.toFloat() } // pcm[i] = i

                    // Whole file [0, 30 s]: t0 = 0, t1 = 480000, mid = 240000,
                    // st = max(0, 240000 - 160000) = 80000, en = min(480000, 400000) = 400000.
                    val whole = embeddingWindow(pcm, startMs = 0, endMs = 30_000)
                    whole.size.shouldBe(320_000)
                    whole[0].shouldBe(80_000f)
                    whole[319_999].shouldBe(399_999f)

                    // Segment [0, 25 s]: mid = 200000, st = 40000, en = min(400000, 360000) = 360000.
                    val partial = embeddingWindow(pcm, startMs = 0, endMs = 25_000)
                    partial.size.shouldBe(320_000)
                    partial[0].shouldBe(40_000f)
                    partial[319_999].shouldBe(359_999f)

                    // Exactly 20 s is NOT capped: it stays a plain slice.
                    val boundary = embeddingWindow(pcm, startMs = 0, endMs = 20_000)
                    boundary.size.shouldBe(320_000)
                    boundary[0].shouldBe(0f)
                    boundary[319_999].shouldBe(319_999f)
                }
            }
        }
    })
