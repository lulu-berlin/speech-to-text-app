package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

private fun tone(sampleCount: Int): FloatArray = FloatArray(sampleCount) { n -> 0.5f * sin(2 * PI * 440 * n / 16_000).toFloat() }

class FbankSpec :
    BehaviorSpec({
        given("N samples of 16 kHz audio") {
            `when`("the fbank features are computed (snip_edges false)") {
                then("there are (N + 80) / 160 frames of 80 values each") {
                    // Kaldi NumFrames with snip_edges = false: (N + frame_shift / 2) / frame_shift, frame_shift = 160.
                    val expectedFrames =
                        mapOf(
                            16_000 to 100, // (16000 + 80) / 160 = 100.5 -> 100
                            79 to 0, // (79 + 80) / 160 = 0.99 -> 0
                            80 to 1, // (80 + 80) / 160 = 1
                            0 to 0,
                        )

                    expectedFrames.forEach { (sampleCount, frameCount) ->
                        val features = fbank(tone(sampleCount))

                        features.size.shouldBe(frameCount)
                        features.forEach { frame -> frame.size.shouldBe(80) }
                    }
                }
            }
        }

        given("the povey window of length 400") {
            `when`("it is computed") {
                then("it matches hand-computed values at its start, center and end") {
                    // Kaldi povey: (0.5 - 0.5 cos(2 pi n / (N - 1)))^0.85 = sin(pi n / 399)^1.7, since 0.5 - 0.5 cos x = sin^2(x / 2).
                    // n = 1:         sin(pi / 399)^1.7 = 0.0078739^1.7 = 0.00026515
                    // n = 199, 200:  sin(pi * 199 / 399)^1.7 = cos(pi / 798)^1.7 = 0.99998683 (peak lies at n = 199.5)
                    val window = poveyWindow(400)

                    window.size.shouldBe(400)
                    window[0].shouldBe(0f)
                    window[1].shouldBe(0.00026515f.plusOrMinus(1e-8f))
                    window[199].shouldBe(0.99998683f.plusOrMinus(1e-6f))
                    window[200].shouldBe(0.99998683f.plusOrMinus(1e-6f))
                    window[398].shouldBe(0.00026515f.plusOrMinus(1e-8f))
                    window[399].shouldBe(0f.plusOrMinus(1e-12f))
                }
            }
        }
    })
