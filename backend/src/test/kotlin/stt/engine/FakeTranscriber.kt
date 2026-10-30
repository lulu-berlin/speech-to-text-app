package stt.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.config.Device
import stt.transcribe.TranscribedChunk
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSegment
import stt.transcribe.WhisperSettings
import java.nio.file.Files
import java.nio.file.Path

/** A [Transcriber] that returns no segments and never spawns whisper-cli; records every call. */
internal class FakeTranscriber : Transcriber {
    /** The audio file of every call, in call order. */
    val audioFiles = mutableListOf<Path>()

    /** The number of `*.flac` inputs beside the audio file when each call started (item 44: exactly one). */
    val inputsAtCall = mutableListOf<Int>()

    /** The whisper-cli settings of every call, in call order (item 32: device and threads per run). */
    val settings = mutableListOf<WhisperSettings>()

    /** The audio file of every call, as the call starts, so a test can wait for the worker to be inside one. */
    val entered = Channel<Path>(Channel.UNLIMITED)

    /** The audio file of every call whose wait was cancelled, so a test can see which process was killed. */
    val cancelled = mutableListOf<Path>()

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

    /** The first call to wait on [hold]; earlier calls run through. */
    var holdFrom: Int = 1

    /** The segments every call returns per task id, offsets relative to the chunk's audio; none by default. */
    var segmentsByTask: Map<Long, List<WhisperSegment>> = emptyMap()

    /** The language each task's runs detect (item 74); a task absent from the map detects none. */
    var detectedLanguageByTask: Map<Long, String> = emptyMap()

    /** The chunk fractions every call reports through `onProgress`, in order, before it returns. */
    var progressScript: List<Double> = emptyList()

    /** True once a call reported progress from the reader coroutine (item 46's contextual-logging seam). */
    var progressReaderLogged = false

    /** The name of the coroutine every call ran in, in call order (item 46: the task's job is named `task-<id>`). */
    val coroutineNames = mutableListOf<String?>()

    /**
     * When set, a cancelled call does not die at once: it reports itself on [dyingStarted] and waits for
     * this before it is recorded in [cancelled], like item 05's runner waiting out its SIGTERM grace period.
     */
    var death: CompletableDeferred<Unit>? = null

    /** The audio file of every cancelled call that has started dying (see [death]). */
    val dyingStarted = Channel<Path>(Channel.UNLIMITED)

    /** When set, the next call throws it instead of returning, like a failing whisper-cli run; then it is cleared. */
    var failNext: Throwable? = null

    /** When set, every call with a GPU chosen reports it unavailable, like whisper-cli on a machine without that GPU (item 32). */
    var gpuUnavailable = false

    val probe = ConcurrencyProbe()

    /** Logs one INFO line per transcription (item 46's contextual-logging seam); a test captures this
     *  logger's events and asserts the MDC carries the task's id. */
    private val logger: Logger = LoggerFactory.getLogger(FakeTranscriber::class.java)

    override suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit,
        onGpuUnavailable: suspend () -> Unit,
    ): TranscribedChunk =
        probe.around {
            coroutineNames.add(currentCoroutineContext()[CoroutineName]?.name)
            logger.info("transcribing {}", audio)
            // A line logged from inside Dispatchers.IO — the chunk/file work thread (item 46's dispatcher test) —
            // the MDC must travel with the coroutine across the switch.
            withContext(Dispatchers.IO) { logger.info("chunk io for {}", audio) }
            inputsAtCall.add(
                Files.list(audio.parent).use { files -> files.filter { it.fileName.toString().endsWith(".flac") }.count().toInt() },
            )
            audioFiles.add(audio)
            this.settings.add(settings)
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
            // The whisper progress arrives on a reader coroutine (ProcessRunner's async(Dispatchers.IO)
            // reader), item 46's deep-leak test: log and report from that coroutine, which the task
            // job does not own directly — the MDC must be inherited by the child.
            coroutineScope {
                val reader =
                    async(Dispatchers.IO) {
                        logger.info("progress reader for {}", audio)
                        progressReaderLogged = true
                        progressScript.forEach(onProgress)
                    }
                reader.await()
            }
            if (gpuUnavailable && settings.device is Device.Gpu) onGpuUnavailable()
            val taskId = taskIdOf(audio.parent)
            TranscribedChunk(segmentsByTask[taskId].orEmpty(), detectedLanguageByTask[taskId])
        }
}
