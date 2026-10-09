package stt.diarization

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
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

        given("a fixed two-tone signal of 1600 integer samples (440 Hz and 3000 Hz)") {
            `when`("the fbank features are computed") {
                then("frames 0, 5 and 9 match the kaldi-native-fbank reference") {
                    // Reference: kaldi-native-fbank, the front-end sherpa-onnx uses for this CAM++ export, with the ADR 0003
                    // options, run by the user outside the repository (ADR 0002; Approval log 2026-10-07).
                    // x[n] = floor(8000 sin(2 pi 440 n / 16000) + 4000 sin(2 pi 3000 n / 16000) + 0.5): whole numbers in the
                    // int16 range, so both sides start from identical samples; frames 0 and 9 include reflected edge samples.
                    val sine = { amplitude: Double, frequency: Double, n: Int -> amplitude * sin(2 * PI * frequency * n / 16_000) }
                    val signal = FloatArray(1600) { n -> floor(sine(8000.0, 440.0, n) + sine(4000.0, 3000.0, n) + 0.5).toFloat() }
                    // Checksums printed by the reference run: the signal here is the one the reference was computed on.
                    signal.sumOf { it.toDouble() }.shouldBe(0.0)
                    signal.sumOf { abs(it.toDouble()) }.shouldBe(8_674_848.0)

                    val features = fbank(signal)

                    features.size.shouldBe(10) // (1600 + 80) / 160
                    KALDI_NATIVE_FBANK_REFERENCE.forEach { (frame, expected) ->
                        expected.size.shouldBe(80) // a malformed reference row must not silently skip bins
                        expected.indices.forEach { bin ->
                            withClue("frame $frame, bin $bin") {
                                features[frame][bin].shouldBe(expected[bin].plusOrMinus(1e-3f))
                            }
                        }
                    }
                }
            }
        }

        given("features of 3 frames and 2 columns") {
            `when`("global-mean normalization is applied") {
                then("every feature column averages to zero") {
                    // Column means: (1 + 3 + 5) / 3 = 3 and (2 + 6 + 10) / 3 = 6.
                    val features =
                        arrayOf(
                            floatArrayOf(1f, 2f),
                            floatArrayOf(3f, 6f),
                            floatArrayOf(5f, 10f),
                        )

                    subtractGlobalMean(features)

                    features.map { it.toList() }.shouldBe(
                        listOf(
                            listOf(-2f, -4f),
                            listOf(0f, 0f),
                            listOf(2f, 4f),
                        ),
                    )
                }
            }
        }
    })

/**
 * kaldi-native-fbank log-mel values of the fixed two-tone signal in FbankSpec: frames 0, 5 and 9, 80 bins each, computed
 * by the user outside the repository with the ADR 0003 options and printed to 6 decimals.
 */
private val KALDI_NATIVE_FBANK_REFERENCE: Map<Int, FloatArray> =
    mapOf(
        0 to
            floats(
                """
                14.880064 15.486762 15.725584 16.034163 16.493004 16.960295 17.442692 17.934532 18.196976 18.807211
                19.462440 20.140436 21.283936 22.619469 23.036188 23.195564 22.086706 20.657625 20.073645 19.484442
                19.236887 18.731380 18.493761 18.288858 17.932129 17.810368 17.463526 17.279583 17.050974 16.728168
                16.472355 16.170197 15.806379 15.309044 14.733662 13.891050 12.524942 12.030869 13.699752 14.752004
                15.602964 16.136980 16.735300 17.160791 17.616079 18.077974 18.495592 18.950466 19.442591 19.990179
                20.694702 21.584297 23.514158 26.184591 25.560074 22.425486 21.419392 20.804773 20.352234 19.992210
                19.701427 19.431536 19.208054 18.987770 18.791708 18.603195 18.420305 18.253965 18.064190 17.889282
                17.709560 17.517481 17.308733 17.095074 16.859522 16.594839 16.291714 15.919255 15.474606 14.865115
                """,
            ),
        5 to
            floats(
                """
                8.314958 9.302351 8.809932 7.712377 8.558906 9.922841 11.292827 11.752098 10.861349 11.542529
                14.538960 16.486044 18.213305 22.462822 23.707247 23.270697 20.532007 16.856091 13.883339 12.106400
                12.472385 10.897300 8.817535 9.729239 8.990619 7.202663 7.904685 7.385234 6.054265 6.784107
                5.654129 5.674798 5.624578 4.584822 5.392212 4.219758 5.345073 4.497164 5.383638 4.849995
                5.824079 5.692017 6.367789 6.669431 6.943349 7.982183 8.202918 9.091886 10.236954 11.182899
                12.618486 14.854067 22.354557 26.402683 25.289692 17.059126 13.577892 11.608389 10.154528 9.030545
                8.077949 7.243598 6.614201 6.127622 5.567173 4.905198 4.972970 4.771955 6.201841 5.565586
                4.860142 5.675951 5.419987 4.563343 5.175820 4.756231 6.162073 7.031028 6.483733 5.856031
                """,
            ),
        9 to
            floats(
                """
                14.980462 16.050900 16.267532 16.411484 16.757957 17.154192 17.613997 18.067400 18.299112 18.860302
                19.495119 20.219921 21.342291 22.765482 23.065033 23.057802 22.046385 20.632895 20.088257 19.489567
                19.230106 18.729723 18.489931 18.280754 17.924002 17.801756 17.452343 17.268944 17.039061 16.713739
                16.458958 16.155327 15.792473 15.293792 14.720933 13.876024 12.517686 12.020001 13.679708 14.738285
                15.584629 16.121843 16.718981 17.141418 17.600220 18.058107 18.476896 18.934345 19.423603 19.970737
                20.674246 21.570583 23.811754 26.328463 25.115879 22.415976 21.400259 20.786070 20.333908 19.972124
                19.681787 19.412878 19.188059 18.968733 18.774117 18.584949 18.400202 18.233097 18.042839 17.872532
                17.690208 17.500437 17.296280 17.075953 16.839899 16.572857 16.260469 15.893653 15.427200 14.823964
                """,
            ),
    )
