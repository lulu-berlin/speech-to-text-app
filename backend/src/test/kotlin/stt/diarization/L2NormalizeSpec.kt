package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class L2NormalizeSpec :
    BehaviorSpec({
        given("an arbitrary embedding vector") {
            `when`("it is L2-normalized") {
                then("its norm becomes 1 and the direction is preserved; a zero vector stays zero; a single non-zero component becomes 1") {
                    // 3-4-5 triangle: norm 5, normalized is [0.6, 0.8].
                    val normalized = l2Normalize(floatArrayOf(3f, 4f))
                    normalized[0].shouldBe(0.6f.plusOrMinus(1e-6f))
                    normalized[1].shouldBe(0.8f.plusOrMinus(1e-6f))

                    val zero = l2Normalize(floatArrayOf(0f, 0f, 0f))
                    zero.toList().shouldBe(listOf(0f, 0f, 0f))

                    val single = l2Normalize(floatArrayOf(5f, 0f, 0f))
                    single[0].shouldBe(1f.plusOrMinus(1e-7f))
                    single[1].shouldBe(0f)
                    single[2].shouldBe(0f)
                }
            }
        }
    })
