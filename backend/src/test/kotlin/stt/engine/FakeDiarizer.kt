package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import stt.db.DiarizationStage
import stt.db.StoredTask
import stt.pause.PauseGate

/** Records the tasks whose DIARIZING phase it ran, without loading any model. */
internal class FakeDiarizer : Diarizer {
    /** The ids of the tasks it diarized, in order. */
    val diarized = mutableListOf<Long>()

    /** The id of every call, as the call starts, so a test can wait for the engine to be inside one. */
    val entered = Channel<Long>(Channel.UNLIMITED)

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

    /** Whether a call has come back from the engine's gate, so a test can see it was held there. */
    var passedGate = false
        private set

    /** The whole-file PCM of every call, in order. */
    val pcms = mutableListOf<FloatArray>()

    /** The speaker-model thread count of every call, in order (item 32). */
    val threads = mutableListOf<Int?>()

    override suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        pcm: FloatArray,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    ) {
        diarized.add(task.id)
        this.threads.add(threads)
        pcms.add(pcm)
        entered.send(task.id)
        hold?.await()
        onStage(DiarizationStage.EMBEDDING)
        onProgress(0.0)
        onProgress(0.5)
        onProgress(1.0)
        onStage(DiarizationStage.CLUSTERING)
        onProgress(0.0)
        onProgress(1.0)
        gate.checkpoint()
        passedGate = true
    }
}
