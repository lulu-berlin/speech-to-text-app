package stt.engine

import stt.db.DiarizationStage
import stt.db.StoredTask
import stt.pause.PauseGate

/**
 * Runs a task's DIARIZING phase (roadmap item 11): labels the speakers of all the task's segments from the
 * whole-file PCM the engine rebuilt from the stored chunk FLACs (ADR 0024) and stores them (items 08/09). The engine's
 * in-JVM pause (ADR 0004) is handed in as [gate]: item 09's embedding and clustering check it, so a paused
 * task stops between segments and merges, and resumes from there. [threads] is the speaker model's intra-op
 * thread count from the settings the task started with (item 32; null = ONNX Runtime's default). A seam, so
 * engine tests drive a fake and the ONNX model never loads in a unit test.
 */
internal interface Diarizer {
    suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        pcm: FloatArray,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    )
}
