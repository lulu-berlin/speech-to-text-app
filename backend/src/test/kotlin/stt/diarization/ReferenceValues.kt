package stt.diarization

/** Whitespace-separated decimal values, as printed by an external reference run (kaldi-native-fbank, sherpa-onnx). */
internal fun floats(text: String): FloatArray =
    text
        .trim()
        .split(Regex("\\s+"))
        .map { it.toFloat() }
        .toFloatArray()
