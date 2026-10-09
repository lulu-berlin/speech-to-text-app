package stt.pause

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Cooperative in-JVM pause gate (ADR 0004): embedding stops between windows. A coroutine passing
 * [checkpoint] while paused suspends until [resume]; the suspend point also receives the embedding
 * coroutine's cancellation, so a cancel lands at the same window boundary as a pause.
 *
 * Infrastructure shared by the features that run pausable work: the diarizer (`stt.diarization`)
 * checks it between embedding windows and merges, and the engine closes it for tasks without an
 * external process (roadmap item 11). Lives outside those features like `stt.db` (ADR 0013).
 */
internal class PauseGate {
    private val paused = MutableStateFlow(false)

    /** Requests a stop at the next [checkpoint]. */
    fun pause() {
        paused.value = true
    }

    /** Lets a suspended [checkpoint] continue with the next window. */
    fun resume() {
        paused.value = false
    }

    /** Suspends while the gate is paused; returns immediately otherwise. */
    suspend fun checkpoint() {
        paused.first { !it }
    }
}
