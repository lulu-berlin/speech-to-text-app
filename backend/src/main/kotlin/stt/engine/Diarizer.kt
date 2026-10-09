package stt.engine

import stt.db.StoredTask

/**
 * Runs a task's DIARIZING phase (roadmap item 11): rebuilds the whole-file PCM from the task's stored chunk
 * FLACs (ADR 0024) and assigns and stores the speakers of all its segments (items 08/09). A seam, so engine
 * tests drive a fake and the ONNX model never loads in a unit test.
 */
fun interface Diarizer {
    suspend fun diarize(task: StoredTask)
}
