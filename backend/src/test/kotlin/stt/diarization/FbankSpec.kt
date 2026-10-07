package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

private fun tone(
    sampleCount: Int,
    frequency: Double = 440.0,
): FloatArray = FloatArray(sampleCount) { n -> 0.5f * sin(2 * PI * frequency * n / 16_000).toFloat() }

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

        given("the mel filterbank for 80 bins between 20 Hz and 7600 Hz at FFT size 512") {
            `when`("it is computed") {
                then("each triangle's first and last non-zero FFT bin match hand-computed positions") {
                    // Kaldi MelBanks: mel(f) = 1127 ln(1 + f / 700); FFT bin i sits at 31.25 * i Hz (16000 / 512);
                    // triangle b spans the open interval (mel_low + b * delta, mel_low + (b + 2) * delta).
                    //   mel_low = mel(20) = 31.749, mel_high = mel(7600) = 2786.99, delta = (mel_high - mel_low) / 81 = 34.015
                    // b = 0:  (20.0 Hz, 64.80 Hz)      -> FFT bins 0.64 .. 2.07   -> first 1,   last 2
                    // b = 40: (1707.97 Hz, 1857.80 Hz) -> FFT bins 54.66 .. 59.45 -> first 55,  last 59
                    // b = 79: (7113.80 Hz, 7600 Hz)    -> FFT bins 227.6 .. 243.2 -> first 228, last 243
                    val banks = melFilterbank()

                    banks.size.shouldBe(80)
                    banks.forEach { weights -> weights.size.shouldBe(257) }
                    val edges =
                        listOf(0, 40, 79).associateWith { bin ->
                            banks[bin].indexOfFirst { it > 0f } to
                                banks[bin].indexOfLast { it > 0f }
                        }
                    edges.shouldBe(mapOf(0 to (1 to 2), 40 to (55 to 59), 79 to (228 to 243)))
                }
            }
        }

        given("one second of silence and one second of a pure tone at the peak of mel triangle 40") {
            `when`("the fbank features are computed") {
                then("silence sits at Kaldi's log floor and the tone's mel bin dominates every frame") {
                    // Kaldi floors mel energies at FLT_EPSILON before the log (dither 0, so silence has zero power):
                    // ln(2^-23) = -23 ln 2 = -15.942385.
                    val silence = fbank(FloatArray(16_000))

                    silence.forEach { frame -> frame.forEach { it.shouldBe((-15.942385f).plusOrMinus(1e-5f)) } }

                    // 1781.25 Hz = FFT bin 57 (57 * 31.25 Hz), nearly on triangle 40's peak mel_low + 41 * delta = 1781.76 Hz;
                    // triangle 39 ends there and triangle 41 starts there. Estimated log energy of bin 40 ~ ln(2000) ~ 7.6:
                    // peak |X| ~ 0.25 * sum(povey) * |1 - 0.97 e^(-jw)| = 0.25 * 212.7 * 0.676 ~ 36, plus leakage into bins 56, 58.
                    val toneFeatures = fbank(tone(16_000, frequency = 1781.25))

                    toneFeatures.forEach { frame ->
                        frame.indices.maxBy { frame[it] }.shouldBe(40)
                        frame[40].shouldBeGreaterThan(0f)
                    }
                }
            }
        }
    })
