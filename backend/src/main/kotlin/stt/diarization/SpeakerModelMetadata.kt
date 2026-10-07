package stt.diarization

private const val SUPPORTED_SAMPLE_RATE = 16_000 // the Kaldi front-end's rate (ADR 0003)
private const val SUPPORTED_FEATURE_NORMALIZE_TYPE = "global-mean" // what subtractGlobalMean implements

/**
 * The CAM++ model's custom ONNX metadata (ADR 0003), as written by sherpa-onnx's speaker-embedding export:
 * `output_dim`, `sample_rate`, `normalize_samples` (non-zero = true), `feature_normalize_type`.
 */
internal data class SpeakerModelMetadata(
    val outputDim: Int,
    val sampleRate: Int,
    val normalizeSamples: Boolean,
    val featureNormalizeType: String,
) {
    companion object {
        /** Parses [custom] and rejects values the front-end does not support, naming the key and the value. */
        fun parse(custom: Map<String, String>): SpeakerModelMetadata {
            val outputDim = requireInt(custom, "output_dim")
            val sampleRate = requireInt(custom, "sample_rate")
            require(sampleRate == SUPPORTED_SAMPLE_RATE) {
                "Speaker model metadata sample_rate must be $SUPPORTED_SAMPLE_RATE, got \"$sampleRate\""
            }
            val featureNormalizeType = requireValue(custom, "feature_normalize_type")
            require(featureNormalizeType == SUPPORTED_FEATURE_NORMALIZE_TYPE) {
                "Speaker model metadata feature_normalize_type must be \"$SUPPORTED_FEATURE_NORMALIZE_TYPE\", got \"$featureNormalizeType\""
            }
            return SpeakerModelMetadata(
                outputDim = outputDim,
                sampleRate = sampleRate,
                normalizeSamples = requireInt(custom, "normalize_samples") != 0,
                featureNormalizeType = featureNormalizeType,
            )
        }

        private fun requireInt(
            custom: Map<String, String>,
            key: String,
        ): Int {
            val raw = requireValue(custom, key)
            return requireNotNull(raw.toIntOrNull()) { "Speaker model metadata $key must be an integer, got \"$raw\"" }
        }

        private fun requireValue(
            custom: Map<String, String>,
            key: String,
        ): String = requireNotNull(custom[key]) { "Speaker model metadata $key is missing" }
    }
}
