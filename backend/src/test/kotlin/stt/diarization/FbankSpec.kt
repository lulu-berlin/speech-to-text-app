package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
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
    })
