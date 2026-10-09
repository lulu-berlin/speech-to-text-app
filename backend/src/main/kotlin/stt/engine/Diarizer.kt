package stt.engine

import stt.db.DiarizationStage
import stt.db.StoredTask
import stt.pause.PauseGate

/**
 * Runs a task's DIARIZING phase (roadmap item 11): rebuilds the whole-file PCM from the task's stored chunk
 * FLACs (ADR 0024) and assigns and stores the speakers of all its segments (items 08/09). The engine's
 * in-JVM pause (ADR 0004) is handed in as [gate]: item 09's embedding and clustering check it, so a paused
 * task stops between segments and merges, and resumes from there. A seam, so engine tests drive a fake and
 * the ONNX model never loads in a unit test.
 */
internal interface Diarizer {
    suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    )
}
