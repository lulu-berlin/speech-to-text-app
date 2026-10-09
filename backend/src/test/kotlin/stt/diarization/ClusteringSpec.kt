package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

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
    })
