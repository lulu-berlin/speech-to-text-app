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

/**
 * [SUPPORTED] with one deviation, written as "key=value" to replace a value or as "key:" to drop the key.
 */
private fun speakerModelMetadata(deviation: String): Map<String, String> =
    if (deviation.endsWith(":")) {
        SUPPORTED - deviation.removeSuffix(":")
    } else {
        SUPPORTED + (deviation.substringBefore("=") to deviation.substringAfter("="))
    }

class SpeakerModelMetadataSpec :
    BehaviorSpec({
        given("model metadata with an unsupported value") {
            `when`("it is parsed") {
                then("it is rejected with an error naming the key and the value") {
                    val cases =
                        listOf(
                            speakerModelMetadata("sample_rate=8000") to listOf("sample_rate", "8000"),
                            speakerModelMetadata("feature_normalize_type=none") to listOf("feature_normalize_type", "none"),
                            speakerModelMetadata("output_dim:") to listOf("output_dim", "missing"),
                            speakerModelMetadata("output_dim=abc") to listOf("output_dim", "abc"),
                        )

                    cases.forEach { (custom, expectedInMessage) ->
                        val error = shouldThrow<IllegalArgumentException> { SpeakerModelMetadata.parse(custom) }
                        expectedInMessage.forEach { error.message.shouldContain(it) }
                    }
                }
            }
        }
        given("model metadata with a missing or non-integer key") {
            `when`("it is parsed") {
                then("it is rejected with an error naming the key and the value") {
                    val cases =
                        listOf(
                            speakerModelMetadata("sample_rate:") to listOf("sample_rate", "missing"),
                            speakerModelMetadata("sample_rate=x") to listOf("sample_rate", "x"),
                            speakerModelMetadata("normalize_samples:") to listOf("normalize_samples", "missing"),
                            speakerModelMetadata("normalize_samples=x") to listOf("normalize_samples", "x"),
                            speakerModelMetadata("feature_normalize_type:") to listOf("feature_normalize_type", "missing"),
                        )

                    cases.forEach { (custom, expectedInMessage) ->
                        val error = shouldThrow<IllegalArgumentException> { SpeakerModelMetadata.parse(custom) }
                        expectedInMessage.forEach { error.message.shouldContain(it) }
                    }
                }
            }
        }
    })
