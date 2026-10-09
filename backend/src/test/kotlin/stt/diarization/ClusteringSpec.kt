package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ClusteringSpec :
    BehaviorSpec({
        given("zero or one clustered segment") {
            `when`("they are clustered in auto mode or with a fixed count") {
                then("every label is 0") {
                    val none = emptyArray<FloatArray>()
                    val one = arrayOf(floatArrayOf(0.6f, 0.8f))

                    clusterSegments(none, k = null, maxSpeakers = 8).shouldBe(emptyList())
                    clusterSegments(none, k = 3, maxSpeakers = 8).shouldBe(emptyList())
                    clusterSegments(one, k = null, maxSpeakers = 8).shouldBe(listOf(0))
                    clusterSegments(one, k = 3, maxSpeakers = 8).shouldBe(listOf(0))
                }
            }
        }

        given("two segments in auto mode") {
            `when`("their merge height is just below the speaker threshold, or at or above it") {
                then("below yields one speaker, at or above yields two speakers") {
                    // The distance of (1, 0) and (c, s) is exactly 1 - c, because their dot product is c. The
                    // largest float c with 1 - c <= 1 - threshold gives the smallest float32 distance at or above
                    // the threshold; the next float up gives the largest distance below it.
                    val boundary = 1.0 - SPEAKER_DISTANCE_THRESHOLD
                    val atOrAbove = boundary.toFloat().let { if (it.toDouble() > boundary) Math.nextDown(it) else it }
                    val below = Math.nextUp(atOrAbove)

                    fun pair(c: Float) = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(c, sqrt(1f - c * c)))

                    clusterSegments(pair(below), k = null, maxSpeakers = 8).shouldBe(listOf(0, 0))
                    clusterSegments(pair(atOrAbove), k = null, maxSpeakers = 8).shouldBe(listOf(0, 1))
                }
            }
        }

        given("four unit vectors at 0°, 20°, 50° and 120°") {
            `when`("they are merged by UPGMA") {
                then("each merge height is the mean distance over all member pairs of the two merged clusters") {
                    fun unit(degrees: Double) = floatArrayOf(cos(Math.toRadians(degrees)).toFloat(), sin(Math.toRadians(degrees)).toFloat())

                    fun distance(degrees: Double) = 1 - cos(Math.toRadians(degrees))
                    // Pairwise distances: 0-1 = d(20°) ≈ 0.060, 1-2 = d(30°) ≈ 0.134, 0-2 = d(50°) ≈ 0.357,
                    // 2-3 = d(70°) ≈ 0.658, 1-3 = d(100°) ≈ 1.174, 0-3 = d(120°) = 1.5.
                    // Merge 1: {0} + {1} at d(20°), the smallest distance.
                    // Merge 2: {0, 1} + {2} at (d(50°) + d(30°)) / 2 ≈ 0.246, below {0, 1}-{3} ≈ 1.337 and {2}-{3} ≈ 0.658.
                    // Merge 3: {0, 1, 2} + {3} at (d(120°) + d(100°) + d(70°)) / 3 ≈ 1.111 (unweighted: every segment
                    // counts once; the weighted WPGMA would give ((d(120°) + d(100°)) / 2 + d(70°)) / 2 ≈ 0.997).
                    val merges = upgmaMerges(arrayOf(unit(0.0), unit(20.0), unit(50.0), unit(120.0)))

                    merges.pairs().shouldBe(listOf(0 to 1, 0 to 2, 0 to 3))
                    merges[0].height.shouldBe(distance(20.0).plusOrMinus(1e-6))
                    merges[1].height.shouldBe(((distance(50.0) + distance(30.0)) / 2).plusOrMinus(1e-6))
                    merges[2].height.shouldBe(((distance(120.0) + distance(100.0) + distance(70.0)) / 3).plusOrMinus(1e-6))
                }
            }
        }

        given("pairs at exactly the same distance") {
            `when`("they are merged by UPGMA") {
                then("the lower-index pair is merged first: the lower row, then the lower column") {
                    // Every tie below is exact in float32: each tied dot product is the same single product 1 * c.
                    val c = 0.8f
                    val s = 0.6f
                    // Disjoint pairs in different rows: 0-1 and 2-3 both at 1 - c; all cross distances are 1.
                    val twoRows =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(c, s, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0f, c, s),
                        )
                    // Two pairs in the same row: 0-1 and 0-2 both at 1 - c; 1-2 at 1 - c², which is larger.
                    val oneRow =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f),
                            floatArrayOf(c, s, 0f),
                            floatArrayOf(c, 0f, s),
                        )

                    upgmaMerges(twoRows).pairs().shouldBe(listOf(0 to 1, 2 to 3, 0 to 2))
                    upgmaMerges(oneRow).pairs().shouldBe(listOf(0 to 1, 0 to 2))
                }
            }
        }

        given("three well-separated groups of two segments") {
            `when`("they are clustered in auto mode") {
                then("they yield three speakers") {
                    // Groups on orthogonal planes: every cross-group distance is exactly 1, above the threshold.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    // Within-group distances 1 - c: 0.2, 0.4, 0.04. Merge heights in order: 0.04, 0.2, 0.4, 1, 1;
                    // gaps 0.16, 0.2, 0.6, 0. The largest gap is gap 2, so k = n - (2 + 1) = 6 - 3 = 3: the cut leaves
                    // the three within-group merges.
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0.6f, 0.8f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
                            floatArrayOf(0f, 0f, 0f, 0f, 0.96f, 0.28f),
                        )

                    clusterSegments(embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1, 1, 2, 2))
                }
            }
        }

        given("two groups of three whose largest height gap lies below the last max_speakers merges") {
            `when`("they are clustered in auto mode with max_speakers 3") {
                then("the larger gap lower in the tree is ignored and the largest gap among the last 3 merges is cut") {
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)

                    // A pair at dot product c plus a third point at dot product t from both: the pair merges at 1 - c,
                    // the third point joins at the mean of two equal distances, 1 - t. Third point: r = a·(p + q) + b·z
                    // with a = t / (1 + c) and b = sqrt(1 - 2t² / (1 + c)), z orthogonal to p and q. Each group lives
                    // in its own three dimensions, so every cross-group distance is exactly 1.
                    fun group(
                        c: Double,
                        t: Double,
                        offset: Int,
                    ): List<FloatArray> {
                        val a = t / (1 + c)
                        val b = sqrt(1 - 2 * t * t / (1 + c))
                        return listOf(
                            doubleArrayOf(1.0, 0.0, 0.0),
                            doubleArrayOf(c, sqrt(1 - c * c), 0.0),
                            doubleArrayOf(a * (1 + c), a * sqrt(1 - c * c), b),
                        ).map { v -> FloatArray(6) { d -> if (d - offset in 0..2) v[d - offset].toFloat() else 0f } }
                    }
                    // Merge heights 0.015 (A pair), 0.55 (B pair), 0.6 (A third), 0.65 (B third), 1 (A + B);
                    // gaps 0.535, 0.05, 0.05, 0.35. Over all gaps the largest is gap 0 (k = 6 - 1 = 5); among the
                    // last max_speakers = 3 gaps (gaps 1..3) it is gap 3, so k = 6 - (3 + 1) = 2: groups A and B.
                    val embeddings = (group(c = 0.985, t = 0.4, offset = 0) + group(c = 0.45, t = 0.35, offset = 3)).toTypedArray()

                    clusterSegments(embeddings, k = null, maxSpeakers = 3).shouldBe(listOf(0, 0, 0, 1, 1, 1))
                }
            }
        }
    })

/** The merged `(i, j)` index pairs, in merge order. */
private fun List<Merge>.pairs() = map { it.i to it.j }
