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
    })
