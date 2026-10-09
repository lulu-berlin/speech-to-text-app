package stt.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSegment
import stt.transcribe.WhisperSettings
import java.nio.file.Path

/** A [Transcriber] that returns no segments and never spawns whisper-cli; records every call. */
internal class FakeTranscriber : Transcriber {
    /** The audio file of every call, in call order. */
    val audioFiles = mutableListOf<Path>()

    /** The audio file of every call, as the call starts, so a test can wait for the worker to be inside one. */
    val entered = Channel<Path>(Channel.UNLIMITED)

    /** The audio file of every call whose wait was cancelled, so a test can see which process was killed. */
    val cancelled = mutableListOf<Path>()

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

    /** The first call to wait on [hold]; earlier calls run through. */
    var holdFrom: Int = 1

    /** The segments every call returns, offsets relative to the chunk's audio; none by default. */
    var segments: List<WhisperSegment> = emptyList()

    /** Segments per task id, so one task can have speech and the next none; [segments] is the fallback. */
    var segmentsByTask: Map<Long, List<WhisperSegment>> = emptyMap()

    /**
     * When set, a cancelled call does not die at once: it reports itself on [dyingStarted] and waits for
     * this before it is recorded in [cancelled], like item 05's runner waiting out its SIGTERM grace period.
     */
    var death: CompletableDeferred<Unit>? = null

    /** The audio file of every cancelled call that has started dying (see [death]). */
    val dyingStarted = Channel<Path>(Channel.UNLIMITED)

    /** When set, the next call throws it instead of returning, like a failing whisper-cli run; then it is cleared. */
    var failNext: Throwable? = null

    val probe = ConcurrencyProbe()

    override suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit,
    ): List<WhisperSegment> =
        probe.around {
            audioFiles.add(audio)
            entered.send(audio)
            try {
                if (audioFiles.size >= holdFrom) hold?.await()
            } catch (e: CancellationException) {
                death?.let { pending ->
                    withContext(NonCancellable) {
                        dyingStarted.send(audio)
                        pending.await()
                    }
                }
                cancelled.add(audio)
                throw e
            }
            failNext?.let { failure ->
                failNext = null
                throw failure
            }
            segmentsByTask[taskIdOf(audio.parent)] ?: segments
        }
}
