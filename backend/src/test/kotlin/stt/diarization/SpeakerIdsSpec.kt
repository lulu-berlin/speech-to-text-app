package stt.diarization

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import stt.db.Segment
import stt.withCapturedLogs
import kotlin.math.sqrt

class SpeakerIdsSpec :
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
                    speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 0, 1, 2, 2))
                }
            }
        }

        given("a short segment between two segments of speaker A whose voice is nearer speaker B") {
            `when`("the speakers are labelled in auto mode") {
                then("the short segment is rescued to B, the nearest centroid, not to its neighbours' speaker") {
                    // Long rows 0, 2, 3, 4 (voices A, A2, B, B2) give heights 0.04, 0.2, 1, gaps 0.16, 0.8, so
                    // k = 4 - (1 + 1) = 2: A = 0, B = 1.
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
                    val embeddings = arrayOf(voiceA, floatArrayOf(0.6f, 0f, 0.8f, 0f), voiceA2, voiceB, voiceB2)

                    speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 1, 0, 1, 1))
                }
            }
        }

        given("only segments shorter than 500 ms, two of speaker A and two of speaker B") {
            `when`("the speakers are labelled in auto mode") {
                then("all segments are clustered and the rescue leaves their labels unchanged") {
                    // Voices A, A2, B, B2: heights 0.04, 0.2, 1, gaps 0.16, 0.8, so k = 4 - (1 + 1) = 2: A = 0, B = 1.
                    // Without the fallback nothing is clustered and every label stays 0.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 300), // A
                            segment(startMs = 300, endMs = 600), // A
                            segment(startMs = 600, endMs = 900), // B
                            segment(startMs = 900, endMs = 1_200), // B
                        )
                    val embeddings = arrayOf(voiceA, voiceA2, voiceB, voiceB2)

                    speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1, 1))
                }
            }
        }

        given("long and short segments whose embeddings are all zero, as for empty PCM") {
            `when`("the speakers are labelled in auto mode") {
                then("no centroid is non-zero, so the short segment keeps its label and labelling does not fail") {
                    // Two long zero rows: dot 0, distance 1 at or above the threshold, so n = 2 gives two speakers.
                    // Both centroids are zero, so the short row keeps its starting label 0.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 2_000),
                            segment(startMs = 2_000, endMs = 2_300), // short
                            segment(startMs = 2_300, endMs = 4_300),
                        )
                    val embeddings = Array(segments.size) { FloatArray(4) }

                    speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1))
                }
            }
        }

        given("a short segment exactly as near to speaker B's centroid as to speaker C's") {
            `when`("the speakers are labelled in auto mode") {
                then("it takes the lower-numbered speaker, B") {
                    // A pair at 1 - 0.96 = 0.04 on dims 0-1; B and C pairs of the same shape at 1 - 0.8 = 0.2 on dims 2-3
                    // and 4-5; every cross distance is exactly 1. Long rows: heights 0.04, 0.2, 0.2, 1, 1, gaps 0.16, 0,
                    // 0.8, 0, so k = 6 - (2 + 1) = 3: A = 0, B = 1, C = 2.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 2_000), // A
                            segment(startMs = 2_000, endMs = 4_000), // A
                            segment(startMs = 4_000, endMs = 4_300), // short
                            segment(startMs = 4_300, endMs = 6_300), // B
                            segment(startMs = 6_300, endMs = 8_300), // B
                            segment(startMs = 8_300, endMs = 10_300), // C
                            segment(startMs = 10_300, endMs = 12_300), // C
                        )
                    // The B and C centroids hold the same values, ≈ (0.949, 0.316), on their own dims; the short row has
                    // the same s on B's and C's first dim, so both dot products are exactly s · 0.949 ≈ 0.671 (0 with A).
                    val s = sqrt(0.5f)
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0.96f, 0.28f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, s, 0f, s, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0.8f, 0.6f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0f, 0f, 0f, 0.8f, 0.6f),
                        )

                    speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1, 1, 1, 2, 2))
                }
            }
        }

        given("speakers A (two long segments and a short one), B (two long segments) and C (one long segment)") {
            `when`("the speakers are labelled in auto mode, once with the real summary and once with a failing one") {
                then(
                    "the summary logs each speaker's within-cosine and the cluster-to-cluster cosines; a failing summary is logged, not thrown",
                ) {
                    // Long rows A, A2, B, B2, C: dots A·A2 = 0.8, B·B2 = 0.96, A2·B2 = 0.6 · 0.28 = 0.168, all others 0.
                    // Merges B pair at 0.04, A pair at 0.2, A-B at (1 + 1 + 1 + 0.832) / 4 = 0.958, then C at 1; gaps
                    // 0.16, 0.758, 0.042, so k = 5 - (1 + 1) = 3: A = 0, B = 1, C = 2. The short row equals A and is
                    // rescued to A, but the summary covers only the clustered rows.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val segments =
                        listOf(
                            segment(startMs = 0, endMs = 2_000), // A
                            segment(startMs = 2_000, endMs = 2_300), // short, A
                            segment(startMs = 2_300, endMs = 4_300), // A2
                            segment(startMs = 4_300, endMs = 6_300), // B
                            segment(startMs = 6_300, endMs = 8_300), // B2
                            segment(startMs = 8_300, endMs = 10_300), // C
                        )
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0.28f, 0.96f, 0f),
                            floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    val expectedSpeakerIds = listOf(0, 0, 0, 1, 1, 2)

                    // Within-cosine: mean off-diagonal dot, 1.0 for a single member. Cross: mean of all A-B dots
                    // = 0.168 / 4 = 0.042; A-C and B-C are 0.
                    withCapturedLogs("stt.diarization.SpeakerSummary", Level.INFO) { events ->
                        speakerIds(segments, embeddings, k = null, maxSpeakers = 8).shouldBe(expectedSpeakerIds)

                        events.map { it.level to it.formattedMessage }.shouldBe(
                            listOf(
                                Level.INFO to "speaker 1: 2 segments, within-cos 0.800",
                                Level.INFO to "speaker 2: 2 segments, within-cos 0.960",
                                Level.INFO to "speaker 3: 1 segments, within-cos 1.000",
                                Level.INFO to "cluster-to-cluster cosine: 1-2 0.042  1-3 0.000  2-3 0.000",
                            ),
                        )
                    }

                    withCapturedLogs("stt.diarization.SpeakerSummary") { events ->
                        speakerIds(segments, embeddings, k = null, maxSpeakers = 8, summary = { _, _ -> error("boom") })
                            .shouldBe(expectedSpeakerIds)

                        events.map { it.level to it.throwableProxy?.message }.shouldBe(listOf(Level.WARN to "boom"))
                    }
                }
            }
        }
    })

// Two voices on orthogonal planes, so every A-B distance is exactly 1: A on dims 0-1 (A-A2 at 1 - 0.8 = 0.2), B on
// dims 2-3 (B-B2 at 1 - 0.96 = 0.04).
private val voiceA = floatArrayOf(1f, 0f, 0f, 0f)
private val voiceA2 = floatArrayOf(0.8f, 0.6f, 0f, 0f)
private val voiceB = floatArrayOf(0f, 0f, 1f, 0f)
private val voiceB2 = floatArrayOf(0f, 0f, 0.96f, 0.28f)

private fun segment(
    startMs: Long,
    endMs: Long,
) = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speakerId = null)
