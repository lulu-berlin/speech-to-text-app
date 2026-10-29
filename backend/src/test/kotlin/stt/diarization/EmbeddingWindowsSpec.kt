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

                    val window = embeddingWindow(pcmSource(pcm), startMs = 500, endMs = 1000)

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
                    val whole = embeddingWindow(pcmSource(pcm), startMs = 0, endMs = 30_000)
                    whole.size.shouldBe(320_000)
                    whole[0].shouldBe(80_000f)
                    whole[319_999].shouldBe(399_999f)

                    // Segment [0, 25 s]: mid = 200000, st = 40000, en = min(400000, 360000) = 360000.
                    val partial = embeddingWindow(pcmSource(pcm), startMs = 0, endMs = 25_000)
                    partial.size.shouldBe(320_000)
                    partial[0].shouldBe(40_000f)
                    partial[319_999].shouldBe(359_999f)

                    // Exactly 20 s is NOT capped: it stays a plain slice.
                    val boundary = embeddingWindow(pcmSource(pcm), startMs = 0, endMs = 20_000)
                    boundary.size.shouldBe(320_000)
                    boundary[0].shouldBe(0f)
                    boundary[319_999].shouldBe(319_999f)
                }
            }
        }
        given("a 25 s PCM and a 40 s segment that ends past the clip's end") {
            `when`("building the embedding window") {
                then("it is the center-capped slice, clamped to the clip's end instead of failing") {
                    // Item 09's cap rule, by hand: dur = min(t1, total) - t0 = 400 000 > cap,
                    // mid = (0 + 640 000) / 2 = 320 000, st = max(0, 320 000 - 160 000) = 160 000,
                    // en = min(640 000, 480 000) = 480 000 — the slice pcm[160 000:480 000] clamps to the
                    // clip's 400 000 samples. The port clamps with the clip's own count: min(t1, total, st + cap) = 400 000.
                    val pcm = FloatArray(400_000) { i -> i.toFloat() } // pcm[i] = i

                    val window = embeddingWindow(pcmSource(pcm), startMs = 0, endMs = 40_000)

                    window.size.shouldBe(240_000)
                    window[0].shouldBe(160_000f)
                    window[239_999].shouldBe(399_999f)
                }
            }
        }
        given("a PCM and segments past its end") {
            `when`("building the embedding window") {
                then("they use the last 2 s; a file shorter than 2 s is centered inside a 2 s window") {
                    val pcm = FloatArray(48_000) { i -> i.toFloat() } // 3 s

                    // Segment past the end (t0 >= total): tail = pcm[16000, 48000).
                    val pastEnd = embeddingWindow(pcmSource(pcm), startMs = 3500, endMs = 4000)
                    pastEnd.size.shouldBe(32_000)
                    pastEnd[0].shouldBe(16_000f)
                    pastEnd[31_999].shouldBe(47_999f)

                    // Segment whose end precedes its start (t1 <= t0): the same tail.
                    val reversed = embeddingWindow(pcmSource(pcm), startMs = 1500, endMs = 1000)
                    reversed.size.shouldBe(32_000)
                    reversed[0].shouldBe(16_000f)
                    reversed[31_999].shouldBe(47_999f)

                    // File shorter than 2 s: the whole audio, centered (offset (32000 - 16000) / 2 = 8000).
                    val shortPcm = FloatArray(16_000) { i -> i.toFloat() }
                    val shortPastEnd = embeddingWindow(pcmSource(shortPcm), startMs = 2000, endMs = 2500)
                    shortPastEnd.size.shouldBe(32_000)
                    shortPastEnd[0].shouldBe(0f)
                    shortPastEnd[7_999].shouldBe(0f)
                    shortPastEnd[8_000].shouldBe(0f)
                    shortPastEnd[23_999].shouldBe(15_999f)
                    shortPastEnd[24_000].shouldBe(0f)

                    // Ending exactly at the file end is not past-the-end: the short segment, centered.
                    val atEnd = embeddingWindow(pcmSource(pcm), startMs = 2500, endMs = 3000)
                    atEnd.size.shouldBe(32_000)
                    atEnd[12_000].shouldBe(40_000f)
                    atEnd[19_999].shouldBe(47_999f)
                }
            }
        }
    })
