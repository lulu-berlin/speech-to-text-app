package stt.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stt.audio.Chunker
import stt.config.Config
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.SegmentRepository
import stt.db.StoredTask
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.transcribe.ChunkTranscription
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSettings
import stt.transcribe.resumePrompt
import java.nio.file.Files
import java.nio.file.Path

/**
 * The processing engine (roadmap item 11): one worker coroutine that drives queued tasks through their
 * phases, one task at a time (ADR 0004 chunking and transcription; ADR 0024 storage).
 *
 * The worker picks the QUEUED task with the lowest `queue_position` and suspends on [wakeups] when there
 * is none, so being idle costs nothing. Every status and phase change is persisted before it is published
 * on [events], which the SSE endpoint (item 12) consumes.
 */
class ProcessingEngine(
    private val scope: CoroutineScope,
    private val config: Config,
    private val tasks: TaskRepository,
    private val chunks: ChunkRepository,
    private val segments: SegmentRepository,
    private val workDirectories: TaskWorkDirectories,
    private val chunker: Chunker,
    private val transcriber: Transcriber,
) {
    private val transcription = ChunkTranscription(transcriber, segments)

    private val _events =
        MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every state change, in order, after it has been persisted. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Signals the worker that there may be work for it (a new upload, a resume, a reorder, a queue resume).
     * Conflated: a signal sent while the worker is busy is not lost, and an extra signal is harmless.
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Starts the single worker coroutine; it runs until [scope] is cancelled. */
    fun start() {
        scope.launch { workerLoop() }
    }

    private suspend fun workerLoop() {
        while (true) {
            val next = tasks.nextQueued()
            if (next == null) {
                wakeups.receive()
            } else {
                runTask(next)
            }
        }
    }

    /** Runs one task through its phases, in order, and leaves it COMPLETED. */
    private suspend fun runTask(task: StoredTask) {
        val id = task.id
        tasks.setRunning(id, now())
        publish(id)
        chunkAudio(id)
        transcribeChunks(id, task)
        tasks.setCompleted(id, now())
        publish(id)
    }

    /**
     * CHUNKING (ADR 0024): decode, plan and export the task's chunks into its work directory, store the
     * decoded duration and the plan with every chunk FLAC, and only then delete the original, which after
     * that commit is no longer the only copy of the audio.
     */
    private suspend fun chunkAudio(id: Long) {
        tasks.setPhase(id, TaskPhase.CHUNKING)
        publish(id)
        val chunked = chunker.chunk(workDirectories.source(id), workDirectories.workDir(id))
        tasks.setDurationMs(id, chunked.durationMs)
        chunks.storePlan(id, chunked.chunks)
        withContext(Dispatchers.IO) { Files.deleteIfExists(workDirectories.source(id)) }
    }

    /**
     * TRANSCRIBING (ADR 0004): each chunk in `idx` order that is not DONE yet, with the previous chunk's
     * stored text as the prompt. The chunk's FLAC is written from the database for whisper-cli and removed
     * again once the chunk is stored (item 07).
     */
    private suspend fun transcribeChunks(
        id: Long,
        task: StoredTask,
    ) {
        tasks.setPhase(id, TaskPhase.TRANSCRIBING)
        publish(id)
        val workDir = workDirectories.workDir(id)
        for (chunk in chunks.findByTask(id)) {
            if (chunk.status == ChunkStatus.DONE) continue
            val audio = workDir.resolve("chunk-${chunk.idx}.flac")
            writeChunkFlac(id, chunk.idx, audio)
            transcription.transcribe(
                taskId = id,
                chunk = chunk,
                settings = settingsFor(task),
                audio = audio,
                outputPrefix = workDir.resolve("chunk-${chunk.idx}"),
                prompt = resumePrompt(segments, id, chunk.idx),
            )
        }
    }

    /** Writes chunk [idx]'s stored FLAC to [target], where whisper-cli reads it (ADR 0024). */
    private suspend fun writeChunkFlac(
        id: Long,
        idx: Int,
        target: Path,
    ) {
        withContext(Dispatchers.IO) {
            Files.newOutputStream(target).use { chunks.copyFlacTo(id, idx, it) }
        }
    }

    /** Persists the change, then publishes the task as it now is. */
    private suspend fun publish(id: Long) {
        tasks.find(id)?.let { _events.emit(Event.TaskUpdated(it)) }
    }

    private fun settingsFor(task: StoredTask) =
        WhisperSettings(
            model = config.whisperModel,
            language = task.task.language,
            threads = config.threads,
            device = config.device,
        )

    private fun now(): Long = System.currentTimeMillis()
}
