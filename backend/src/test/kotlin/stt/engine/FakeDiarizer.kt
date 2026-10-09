package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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

    override suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
    ) {
        diarized.add(task.id)
        entered.send(task.id)
        hold?.await()
        gate.checkpoint()
        passedGate = true
    }
}
