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
    })

private fun segment(
    startMs: Long,
    endMs: Long,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speaker = null)
