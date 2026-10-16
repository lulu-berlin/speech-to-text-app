package stt.engine

import stt.db.DiarizationStage
import stt.db.StoredTask
import stt.diarization.PcmSource
import stt.pause.PauseGate

/**
 * Runs a task's DIARIZING phase (roadmap item 11): labels the speakers of all the task's segments from the
 * task's audio and stores them (items 08/09). [audio] reads that audio — the task's stored canonical clip
 * (item 44) — one speaker window at a time (item 43), so a long clip is never materialized in heap. The
 * engine's in-JVM pause (ADR 0004) is handed in as [gate]: item 09's embedding and clustering check it, so a
 * paused task stops between segments and merges, and resumes from there. [threads] is the speaker model's
 * intra-op thread count from the settings the task started with (item 32; null = ONNX Runtime's default). A
 * seam, so engine tests drive a fake and the ONNX model never loads in a unit test.
 */
internal interface Diarizer {
    suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        audio: PcmSource,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    )
}
