package stt.diarization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/** Metadata the front-end supports, as in the CAM++ model file (ADR 0003). */
private val SUPPORTED =
    mapOf(
        "output_dim" to "512",
        "sample_rate" to "16000",
        "normalize_samples" to "1",
        "feature_normalize_type" to "global-mean",
    )

class SpeakerModelMetadataSpec :
    BehaviorSpec({
        given("model metadata with an unsupported value") {
            `when`("it is parsed") {
                then("it is rejected with an error naming the key and the value") {
                    val cases =
                        listOf(
                            SUPPORTED + ("sample_rate" to "8000") to listOf("sample_rate", "8000"),
                            SUPPORTED + ("feature_normalize_type" to "none") to listOf("feature_normalize_type", "none"),
                            SUPPORTED - "output_dim" to listOf("output_dim", "missing"),
                            SUPPORTED + ("output_dim" to "abc") to listOf("output_dim", "abc"),
                        )

                    cases.forEach { (custom, expectedInMessage) ->
                        val error = shouldThrow<IllegalArgumentException> { SpeakerModelMetadata.parse(custom) }
                        expectedInMessage.forEach { error.message.shouldContain(it) }
                    }
                }
            }
        }
    })
