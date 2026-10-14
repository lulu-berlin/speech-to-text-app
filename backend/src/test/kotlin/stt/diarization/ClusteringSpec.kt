package stt.diarization

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
                    // Pairwise distances: 0-1 = d(20°) ≈ 0.060, 1-2 = d(30°) ≈ 0.134, 0-2 = d(50°) ≈ 0.357,
                    // 2-3 = d(70°) ≈ 0.658, 1-3 = d(100°) ≈ 1.174, 0-3 = d(120°) = 1.5.
                    // Merge 1: {0} + {1} at d(20°), the smallest distance.
                    // Merge 2: {0, 1} + {2} at (d(50°) + d(30°)) / 2 ≈ 0.246, below {0, 1}-{3} ≈ 1.337 and {2}-{3} ≈ 0.658.
                    // Merge 3: {0, 1, 2} + {3} at (d(120°) + d(100°) + d(70°)) / 3 ≈ 1.111 (unweighted: every segment
                    // counts once; the weighted WPGMA would give ((d(120°) + d(100°)) / 2 + d(70°)) / 2 ≈ 0.997).
                    val merges = upgmaMerges(arrayOf(unitAt(0.0), unitAt(20.0), unitAt(50.0), unitAt(120.0)))

                    merges.pairs().shouldBe(listOf(0 to 1, 0 to 2, 0 to 3))
                    merges[0].height.shouldBe(distanceAt(20.0).plusOrMinus(1e-6))
                    merges[1].height.shouldBe(((distanceAt(50.0) + distanceAt(30.0)) / 2).plusOrMinus(1e-6))
                    merges[2].height.shouldBe(((distanceAt(120.0) + distanceAt(100.0) + distanceAt(70.0)) / 3).plusOrMinus(1e-6))
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
                    // Every cross-group distance is exactly 1, above the threshold.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    // Merge heights in order: 0.04, 0.2, 0.4, 1, 1; gaps 0.16, 0.2, 0.6, 0. The largest gap is gap 2,
                    // so k = n - (2 + 1) = 6 - 3 = 3: the cut leaves the three within-group merges.
                    clusterSegments(threeOrthogonalPairs(), k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 1, 1, 2, 2))
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

        given("orthogonal segments, so every merge height is 1 and every gap is 0") {
            `when`("they are clustered in auto mode") {
                then("the count is clamped to max_speakers") {
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)

                    fun orthogonal(n: Int) = Array(n) { row -> FloatArray(n) { if (it == row) 1f else 0f } }
                    // All gaps tie at 0, so the first gap of the window wins: idx = start = max(0, (n - 2) - maxSpeakers).
                    // n = 3, maxSpeakers = 1: idx 0, k = 3 - 1 = 2, clamped to 1.
                    // n = 4, maxSpeakers = 2: idx 0, k = 4 - 1 = 3, clamped to 2. The merges are (0, 1), (0, 2), (0, 3)
                    // (ties go to the lower pair); cutting after the first two leaves {0, 1, 2} and {3}.
                    clusterSegments(orthogonal(3), k = null, maxSpeakers = 1).shouldBe(listOf(0, 0, 0))
                    clusterSegments(orthogonal(4), k = null, maxSpeakers = 2).shouldBe(listOf(0, 0, 0, 1))
                }
            }
        }

        given("three segments whose last merge is below the speaker threshold") {
            `when`("they are clustered in auto mode") {
                then("they yield one speaker (ADR 0027), although the largest-gap rule alone would split them") {
                    // Unit vectors at 0°, 10° and 30°: merge 1 is {0} + {1} at d(10°) ≈ 0.015; merge 2 is {0, 1} + {2}
                    // at (d(30°) + d(20°)) / 2 ≈ 0.097, the last and highest merge. The single gap would give
                    // k = 3 - (0 + 1) = 2; below the threshold the count is 1.
                    val lastHeight = (distanceAt(30.0) + distanceAt(20.0)) / 2
                    lastHeight.shouldBeLessThan(SPEAKER_DISTANCE_THRESHOLD)

                    clusterSegments(arrayOf(unitAt(0.0), unitAt(10.0), unitAt(30.0)), k = null, maxSpeakers = 8).shouldBe(listOf(0, 0, 0))
                }
            }
        }

        given("three groups of two segments on orthogonal planes") {
            `when`("they are clustered with a fixed count and max_speakers 1") {
                then("they yield exactly k speakers, k clamped to [1, n], ignoring max_speakers") {
                    // Merges in order: (4, 5) at 0.04, (0, 1) at 0.2, (2, 3) at 0.4, then (0, 2) and (0, 4) at 1 (ties
                    // go to the lower pair). A count k applies the first 6 - k merges.
                    val embeddings = threeOrthogonalPairs()

                    // k = 2: four merges leave {0, 1, 2, 3} and {4, 5}.
                    clusterSegments(embeddings, k = 2, maxSpeakers = 1).shouldBe(listOf(0, 0, 0, 0, 1, 1))
                    // k = 4: two merges leave {0, 1}, {2}, {3} and {4, 5}.
                    clusterSegments(embeddings, k = 4, maxSpeakers = 1).shouldBe(listOf(0, 0, 1, 2, 3, 3))
                    clusterSegments(embeddings, k = 1, maxSpeakers = 1).shouldBe(listOf(0, 0, 0, 0, 0, 0))
                    // Clamped: k = 0 counts as 1, k = 10 as n = 6 (no merge applied).
                    clusterSegments(embeddings, k = 0, maxSpeakers = 1).shouldBe(listOf(0, 0, 0, 0, 0, 0))
                    clusterSegments(embeddings, k = 10, maxSpeakers = 1).shouldBe(listOf(0, 1, 2, 3, 4, 5))
                }
            }
        }

        given("speakers A, B, A, B in segment order, where the B pair merges first") {
            `when`("they are clustered in auto mode or with a fixed count of 2") {
                then("A, appearing first, is speaker 0 and B is speaker 1") {
                    // A and B on orthogonal planes (cross distances exactly 1, above the threshold); A pair (rows 0, 2)
                    // at 1 - 0.8 = 0.2, B pair (rows 1, 3) at 1 - 0.96 = 0.04. Merges: (1, 3) at 0.04, (0, 2) at 0.2,
                    // (0, 1) at 1; gaps 0.16, 0.8, so auto k = 4 - (1 + 1) = 2, like the fixed count. The cut keeps the
                    // B pair's merge first, but numbering follows segment order: row 0 (A) gets 0, row 1 (B) gets 1.
                    SPEAKER_DISTANCE_THRESHOLD.shouldBeLessThan(1.0)
                    val embeddings =
                        arrayOf(
                            floatArrayOf(1f, 0f, 0f, 0f),
                            floatArrayOf(0f, 0f, 1f, 0f),
                            floatArrayOf(0.8f, 0.6f, 0f, 0f),
                            floatArrayOf(0f, 0f, 0.96f, 0.28f),
                        )

                    clusterSegments(embeddings, k = null, maxSpeakers = 8).shouldBe(listOf(0, 1, 0, 1))
                    clusterSegments(embeddings, k = 2, maxSpeakers = 8).shouldBe(listOf(0, 1, 0, 1))
                }
            }
        }

        given("random matrices and a tied matrix") {
            `when`("the merges are compared with the naive O(n³) reference") {
                then("every height matches, and every pair matches except where an exact height tie lets it differ") {
                    // Differential over random L2-normalized 512-d matrices: exact double ties are effectively impossible, so
                    // the pair order must match the reference exactly; the hand-built tied matrix exercises the accepted exception
                    // (a pair may differ only where its height ties with another in the same merge sequence).
                    for ((seed, n) in listOf(1L to 2, 2L to 3, 3L to 5, 4L to 8, 5L to 17)) {
                        val embeddings = randomNormalizedMatrix(seed, n)
                        withClue("random seed $seed, n $n") {
                            assertMatchesReference(upgmaMerges(embeddings), naiveUpgmaMerges(embeddings))
                        }
                    }
                    withClue("tied matrix") {
                        val embeddings = tiedMatrix()
                        assertMatchesReference(
                            upgmaMerges(embeddings),
                            naiveUpgmaMerges(embeddings),
                        )
                    }
                }
            }
        }

        given("a tie-heavy matrix") {
            `when`("clustering runs twice on the same input") {
                then("both runs produce the identical merge sequence") {
                    // The four merges tied at 0.2 and the three tied at 1 (see [tiedMatrix]) may pair differently from
                    // the item 09 order — accepted — but a given input must always resolve the same way.
                    upgmaMerges(tiedMatrix()).shouldBe(upgmaMerges(tiedMatrix()))
                }
            }
        }

        given("random L2-normalized matrices without exact distance ties") {
            `when`("the labels are compared with the naive reference, auto and fixed counts") {
                then("they match") {
                    for ((seed, n) in listOf(11L to 4, 12L to 9, 13L to 20, 14L to 36)) {
                        val embeddings = randomNormalizedMatrix(seed, n)
                        for (k in listOf(null, 2, 3, 7)) {
                            withClue("seed $seed, n $n, k $k") {
                                clusterSegments(embeddings, k = k, maxSpeakers = 8)
                                    .shouldBe(naiveClusterSegments(embeddings, k = k, maxSpeakers = 8))
                            }
                        }
                    }
                }
            }
        }
    })

/**
 * Three groups of two unit vectors, each group on its own plane, so every cross-group distance is exactly 1. Within-group
 * distances `1 - c`: 0.2 (rows 0-1), 0.4 (rows 2-3), 0.04 (rows 4-5).
 */
private fun threeOrthogonalPairs() =
    arrayOf(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0.8f, 0.6f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 0.6f, 0.8f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0.96f, 0.28f),
    )

/** The merged `(i, j)` index pairs, in merge order. */
private fun List<Merge>.pairs() = map { it.i to it.j }

/** The unit vector at [degrees] in the plane. */
private fun unitAt(degrees: Double) = floatArrayOf(cos(Math.toRadians(degrees)).toFloat(), sin(Math.toRadians(degrees)).toFloat())

/** The cosine distance `1 - cos` of two unit vectors [degrees] apart. */
private fun distanceAt(degrees: Double) = 1 - cos(Math.toRadians(degrees))

/**
 * Asserts [merges] matches the naive reference: every height matches (within 1e-12, because the chain-on-demand
 * distance arithmetic rounds differently from the weighted-matrix updates)and every pair matches, except that a pair may
 * differ where its height ties exactly with another height in the same sequence (recognized within 1e-9; the accepted,
 * documented exception of the NN-chain switch).
 */
private fun assertMatchesReference(
    merges: List<Merge>,
    naive: List<Merge>,
) {
    naive.size.shouldBe(merges.size)
    for (i in naive.indices) {
        withClue(
            "merge $i: within-tolerance heights but pair (${merges[i].i}, ${merges[i].j}) vs (${naive[i].i}, ${naive[i].j})",
        ) {
            abs(merges[i].height - naive[i].height).shouldBeLessThan(1e-12)
            if (merges[i].i != naive[i].i || merges[i].j != naive[i].j) {
                withClue("pair differs without an exact height tie") {
                    naive.indices
                        .any { k -> k != i && abs(naive[i].height - naive[k].height) < 1e-9 }
                        .shouldBeTrue()
                }
            }
        }
    }
}

/** [n] random rows in 512 dims,L2-normalized (as embeddings are), deterministic per [seed]. */
private fun randomNormalizedMatrix(
    seed: Long,
    n: Int,
): Array<FloatArray> {
    val random = Random(seed)
    return Array(n) {
        l2Normalize(FloatArray(512) { random.nextFloat() * 2f - 1f })
    }
}

/**
 * Eight unit vectors as four pairs on orthogonal planes, all at distance   1 -   0.8; every cross distance is
 * exactly 1: four merges tie at   0.2and three at   1, so the pair order at each tied height is free.
 */
private fun tiedMatrix(): Array<FloatArray> {
    fun row(
        base: Int,
        first: Float,
    ): FloatArray =
        FloatArray(8) { d ->
            when {
                d == base -> first
                d == base + 1 -> 1f - first
                else -> 0f
            }
        }
    return (0 until 4)
        .flatMap { p ->
            val base = p * 2
            listOf(
                row(base, 1f),
                row(base, 0.2f),
            )
        }.toTypedArray()
}
