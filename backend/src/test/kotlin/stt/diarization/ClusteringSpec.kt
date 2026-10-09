package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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
    })
