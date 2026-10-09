package stt.diarization

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.Segment
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker embeddings over [SpeakerModel]: one L2-normalized vector per segment, from each segment's
 * window through the Kaldi fbank + global-mean front-end and one ONNX Runtime inference call per window.
 *
 * Ported from oracle `embed_windows` (docs/oracle/transcribe.py), minus the length-sorted batching, which is a
 * PyTorch memory workaround, not behavior. Window values are 16-bit-quantized PCM in `[-1, 1]` (ADR 0024); like
 * sherpa-onnx's feature extractor (`features.cc` `AcceptWaveform`), they go in unchanged when the model metadata
 * says `normalize_samples` (all 3D-Speaker models, CAM++ included) and are scaled to the int16 range (× 32768)
 * otherwise. The ONNX session is reused for every window; calls run on [Dispatchers.Default] (CPU-bound).
 */
internal class Embeddings(
    private val model: SpeakerModel,
) {
    private val env = OrtEnvironment.getEnvironment()
    private val inputName = model.session.getInputNames().single()
    private val outputName = model.session.getOutputNames().single()

    /**
     * One embedding per [segments] segment, in segment order. Empty [pcm] yields every embedding all zeros
     * (oracle `embed_windows` with `total <= 0`), without touching the session.
     *
     * [gate] (ADR 0004) is checked before every window: while it is paused the embedding suspends between
     * windows, and a cancellation of this coroutine lands at the same boundary. [onWindowDone] reports
     * `(windows done, total windows)` after each window.
     */
    suspend fun embed(
        pcm: FloatArray,
        segments: List<Segment>,
        gate: PauseGate? = null,
        onWindowDone: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Array<FloatArray> {
        if (pcm.isEmpty()) {
            return Array(segments.size) { FloatArray(model.metadata.outputDim) }
        }
        val embeddings = ArrayList<FloatArray>(segments.size)
        withContext(Dispatchers.Default) {
            segments.forEachIndexed { index, segment ->
                gate?.checkpoint()
                val window = embeddingWindow(pcm, segment.startMs, segment.endMs)
                val samples = if (model.metadata.normalizeSamples) window else FloatArray(window.size) { window[it] * 32768f }
                val features = fbank(samples)
                subtractGlobalMean(features)
                embeddings.add(l2Normalize(infer(features)))
                onWindowDone(index + 1, segments.size)
            }
        }
        return embeddings.toTypedArray()
    }

    /** Runs the session on `features` shaped `[1, T, [MEL_BINS]]` and returns the raw output tensor. */
    private fun infer(features: Array<FloatArray>): FloatArray {
        val bins = features[0].size
        val flat = FloatArray(features.size * bins) { i -> features[i / bins][i % bins] }
        val shape = longArrayOf(1L, features.size.toLong(), bins.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
            model.session.run(mapOf(inputName to input)).use { result ->
                val output = (result.get(outputName).orElseThrow() as OnnxTensor).getFloatBuffer()
                FloatArray(output.remaining()) { output.get(it) }
            }
        }
    }
}

/**
 * L2-normalizes [vector] in place; a zero vector (no energy) stays all zeros, as the oracle sets the norm to 1
 * only when it is non-zero (`embed_windows`: `norms[norms == 0] = 1.0`).
 */
internal fun l2Normalize(vector: FloatArray): FloatArray {
    var sumSquares = 0.0
    for (value in vector) sumSquares += value.toDouble() * value.toDouble()
    val norm = sqrt(sumSquares)
    if (norm == 0.0) return vector
    for (i in vector.indices) vector[i] = (vector[i].toDouble() / norm).toFloat()
    return vector
}
