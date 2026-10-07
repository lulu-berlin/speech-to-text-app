package stt.diarization

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
        fun parse(custom: Map<String, String>): SpeakerModelMetadata =
            SpeakerModelMetadata(
                outputDim = custom.getValue("output_dim").toInt(),
                sampleRate = custom.getValue("sample_rate").toInt(),
                normalizeSamples = custom.getValue("normalize_samples").toInt() != 0,
                featureNormalizeType = custom.getValue("feature_normalize_type"),
            )
    }
}
