package stt.diarization

import kotlinx.coroutines.channels.Channel

/**
 * Cooperative in-JVM pause gate (ADR 0004): embedding stops between windows. A coroutine passing
 * [checkpoint] while paused suspends until [resume]; the suspend point also receives the embedding
 * coroutine's cancellation, so a cancel lands at the same window boundary as a pause.
 *
 * [pause] and [resume] are synchronized with the flag so a resume token can never be released by a
 * later pause (a stale token is dropped on pause); only the embedding coroutine suspends at [checkpoint].
 */
internal class PauseGate {
    private val signal = Channel<Unit>(capacity = 1)
    private var paused = false

    /** Requests a stop at the next [checkpoint]. */
    fun pause() {
        synchronized(this) {
            paused = true
            signal.tryReceive() // drop any resume token that raced this pause
        }
    }

    /** Lets a suspended [checkpoint] continue with the next window. */
    fun resume() {
        synchronized(this) {
            if (paused) {
                paused = false
                signal.trySend(Unit)
            }
        }
    }

    /** Suspends while the gate is paused; returns immediately otherwise. */
    suspend fun checkpoint() {
        while (true) {
            val isPaused = synchronized(this) { paused }
            if (!isPaused) return
            signal.receive()
        }
    }
}
