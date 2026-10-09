package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import stt.db.Segment

class SpeakerLabelsSpec :
    BehaviorSpec({
        given("a 499 ms segment and a 500 ms segment, each with a voice of its own, among two long-segment speakers") {
            `when`("the speakers are labelled in auto mode") {
                then("the 499 ms segment is not clustered and takes no speaker slot; the 500 ms segment is clustered") {
                    // Every direction below is orthogonal to the others except within the A pair (distance 1 - 0.8 = 0.2)
                    // and the B pair (1 - 0.96 = 0.04), so every other distance is exactly 1, above the threshold.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 499), // C, short
                            segment(startMs = 1_000, endMs = 3_000), // A
                            segment(startMs = 3_000, endMs = 5_000), // A
                            segment(startMs = 5_000, endMs = 5_500), // D, exactly 500 ms
                            segment(startMs = 6_000, endMs = 8_000), // B
                            segment(startMs = 8_000, endMs = 10_000), // B
                        )
                    val embeddings =
                        arrayOf(
                            floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
                            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f),
                            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0.96f, 0.28f, 0f, 0f),
                        )
                    // Clustered rows 1-5 (A, A, D, B, B): merges B pair at 0.04, A pair at 0.2, then two at 1; gaps
                    // 0.16, 0.8, 0, so k = 5 - (1 + 1) = 3: A = 0, D = 1, B = 2 by first appearance. Row 0 is not
                    // clustered and keeps 0 (every centroid is orthogonal to it, so no centroid is nearer than the first).
                    // Clustering row 0 as well would give four speakers, [0, 1, 1, 2, 3, 3]; leaving row 3 out would give
                    // two, [0, 0, 0, 0, 1, 1].
                    speakerLabels(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 0, 1, 2, 2))
                }
            }
        }

        given("a short segment between two segments of speaker A whose voice is nearer speaker B") {
            `when`("the speakers are labelled in auto mode") {
                then("the short segment is rescued to B, the nearest centroid, not to its neighbours' speaker") {
                    // A on dims 0-1 (pair at 0.2), B on dims 2-3 (pair at 0.04), cross distances exactly 1: the long rows
                    // 0, 2, 3, 4 give heights 0.04, 0.2, 1, gaps 0.16, 0.8, so k = 4 - (1 + 1) = 2: A = 0, B = 1.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 2_000), // A
                            segment(startMs = 2_000, endMs = 2_300), // short
                            segment(startMs = 2_300, endMs = 4_300), // A
                            segment(startMs = 4_300, endMs = 6_300), // B
                            segment(startMs = 6_300, endMs = 8_300), // B
                        )
                    // Centroids: A = (1.8, 0.6) / sqrt(3.6) ≈ (0.949, 0.316) on dims 0-1; B = (1.96, 0.28) / sqrt(3.92)
                    // ≈ (0.990, 0.141) on dims 2-3. The short row (0.6, 0, 0.8, 0) has dot 0.6 · 0.949 ≈ 0.569 with A
                    // and 0.8 · 0.990 ≈ 0.792 with B, so it gets B = 1 (it would stay 0 without the rescue).
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(0.6f, 0f, 0.8f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0f, 0.96f, 0.28f),
                        )

                    speakerLabels(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 1, 0, 1, 1))
                }
            }
        }

        given("only segments shorter than 500 ms, two of speaker A and two of speaker B") {
            `when`("the speakers are labelled in auto mode") {
                then("all segments are clustered and the rescue leaves their labels unchanged") {
                    // A pair at 1 - 0.8 = 0.2 on dims 0-1, B pair at 1 - 0.96 = 0.04 on dims 2-3, cross distances exactly 1:
                    // heights 0.04, 0.2, 1, gaps 0.16, 0.8, so k = 4 - (1 + 1) = 2: A = 0, B = 1. Without the fallback
                    // nothing is clustered and every label stays 0.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 300), // A
                            segment(startMs = 300, endMs = 600), // A
                            segment(startMs = 600, endMs = 900), // B
                            segment(startMs = 900, endMs = 1_200), // B
                        )
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0f, 0.96f, 0.28f),
                        )

                    speakerLabels(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1, 1))
                }
            }
        }
    })

private fun segment(
    startMs: Long,
    endMs: Long,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speaker = null)
