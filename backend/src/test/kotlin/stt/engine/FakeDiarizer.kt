package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.db.DiarizationStage
import stt.db.StoredTask
import stt.diarization.PcmSource
import stt.pause.PauseGate

/** Records the tasks whose DIARIZING phase it ran, without loading any model. */
internal class FakeDiarizer : Diarizer {
    /** Logs one line per diarization from Dispatchers.Default (item 46's contextual-logging seam). */
    private val logger: Logger = LoggerFactory.getLogger(FakeDiarizer::class.java)

    /** The ids of the tasks it diarized, in order. */
    val diarized = mutableListOf<Long>()

    /** The id of every call, as the call starts, so a test can wait for the engine to be inside one. */
    val entered = Channel<Long>(Channel.UNLIMITED)

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

    /** Whether a call has come back from the engine's gate, so a test can see it was held there. */
    var passedGate = false
        private set

    /** The audio source of every call, in order (item 43: the clip the call reads its windows from). */
    val sources = mutableListOf<PcmSource>()

    /** The speaker-model thread count of every call, in order (item 32). */
    val threads = mutableListOf<Int?>()

    override suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        audio: PcmSource,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    ) {
        // A line logged from inside Dispatchers.Default — the embedding loop's thread in a real run
        // (item 46's dispatcher test): the MDC must travel with the coroutine across the switch.
        withContext(Dispatchers.Default) { logger.info("embedding task {}", task.id) }
        diarized.add(task.id)
        this.threads.add(threads)
        sources.add(audio)
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
