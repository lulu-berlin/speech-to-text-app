package stt.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import stt.audio.Chunker
import stt.config.Config
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.StoredTask
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.process.ProcessControl
import stt.queue.TaskStatus
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
    private val queueState: QueueStateRepository,
    private val workDirectories: TaskWorkDirectories,
    private val chunker: Chunker,
    private val transcriber: Transcriber,
    private val processControl: ProcessControl,
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

    /** The task whose phases are running and the job running them, so [cancelTask] can kill its process. */
    private class Running(
        val id: Long,
        val job: Job,
    )

    private val runningLock = Mutex()
    private var running: Running? = null

    /** Starts the single worker coroutine; it runs until [scope] is cancelled. */
    fun start() {
        scope.launch { workerLoop() }
    }

    /**
     * Tells the worker that the queue may have changed — a new upload, a resume, a reorder, a queue
     * resume — so it looks again instead of waiting. Callers do not need to be precise: the signal is
     * conflated, so repeating it is free and a signal sent while the worker is busy survives until it
     * next checks the queue.
     */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * Pauses the queue: no further task starts until [resumeQueue], and the process of a task already
     * running is frozen (SIGSTOP; a no-op when no process is in flight).
     */
    suspend fun pauseQueue() {
        queueState.setPaused(true)
        processControl.pause()
    }

    /**
     * Resumes the queue and wakes the worker: the frozen process continues and the next queued task starts.
     */
    suspend fun resumeQueue() {
        queueState.setPaused(false)
        processControl.resume()
        wake()
    }

    /**
     * Pauses a task: its process is frozen and its status becomes PAUSED; the phase it is in stays, so
     * a paused task can still say where it stopped. A QUEUED task only changes status (no process is in
     * flight); only a QUEUED or RUNNING task can be paused (item 04's table).
     */
    suspend fun pauseTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.PAUSED) {
            tasks.setStatus(id, TaskStatus.PAUSED)
            publish(id)
            processControl.pause()
            TaskCommandOutcome.Accepted
        }

    /**
     * Resumes a paused task at once: item 04's table has no `PAUSED → RUNNING`, so the task is written as
     * QUEUED and immediately as RUNNING again, and it continues where it stopped even when another QUEUED
     * task is ahead of it — its worker is still inside it. Neither write touches the phase: only the
     * queue-level QUEUED (retry, restart recovery) drops it, so a resume cannot lose a stopped task's phase.
     */
    suspend fun resumeTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.QUEUED) {
            tasks.setStatus(id, TaskStatus.QUEUED)
            publish(id)
            tasks.setStatus(id, TaskStatus.RUNNING)
            publish(id)
            processControl.resume()
            TaskCommandOutcome.Accepted
        }

    /**
     * Cancels a task: its phase job is cancelled, which kills the task's process (item 05), and the task
     * becomes CANCELED with `finished_at` set; the phase it stopped in stays. It returns once the process
     * is gone and the status is stored, so item 12's `DELETE` can cancel a pending task and then delete it.
     * A cancel that races a completion loses: `cancelIfPossible` refuses a task that has finished.
     */
    suspend fun cancelTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.CANCELED) {
            // NonCancellable: a cancel asked for explicitly must kill the process and store the status even
            // when the caller itself is being cancelled (item 12's DELETE, or a shutting-down application).
            withContext(NonCancellable) {
                runningJob(id)?.cancelAndJoin()
                if (tasks.cancelIfPossible(id, now())) publish(id)
            }
            val after = tasks.find(id)?.task?.status ?: return@command TaskCommandOutcome.Unknown
            if (after == TaskStatus.CANCELED) {
                TaskCommandOutcome.Accepted
            } else {
                TaskCommandOutcome.Illegal(after, TaskStatus.CANCELED)
            }
        }

    /**
     * Runs [body] when task [id] exists and its status allows changing to [to] (item 04's table);
     * otherwise reports [TaskCommandOutcome.Unknown] or [TaskCommandOutcome.Illegal] without doing anything.
     */
    private suspend fun command(
        id: Long,
        to: TaskStatus,
        body: suspend () -> TaskCommandOutcome,
    ): TaskCommandOutcome {
        val status = tasks.find(id)?.task?.status ?: return TaskCommandOutcome.Unknown
        if (!status.canTransitionTo(to)) return TaskCommandOutcome.Illegal(status, to)
        return body()
    }

    /**
     * The task to run next: nothing while the queue is paused, otherwise the QUEUED task with the
     * lowest `queue_position`.
     */
    private suspend fun nextTask(): StoredTask? =
        if (queueState.isPaused()) {
            null
        } else {
            tasks.nextQueued()
        }

    private suspend fun workerLoop() {
        while (true) {
            val next = nextTask()
            if (next == null) {
                wakeups.receive()
            } else {
                runInJob(next)
            }
        }
    }

    /**
     * Runs [task]'s phases in a job of their own and waits for it. The job is what [cancelTask] cancels:
     * cancelling the coroutine inside item 05's `run` is what kills the task's process (ADR 0020).
     */
    private suspend fun runInJob(task: StoredTask) {
        val job = scope.launch { runTask(task) }
        runningLock.withLock { running = Running(task.id, job) }
        try {
            job.join()
        } finally {
            runningLock.withLock { running = null }
        }
    }

    /** The job running [id]'s phases, or null when that task is not the one being processed. */
    private suspend fun runningJob(id: Long): Job? = runningLock.withLock { running?.takeIf { it.id == id }?.job }

    /**
     * Runs one task through its phases, in order, and leaves it COMPLETED. Any failure other than a cancel
     * (a non-zero exit, unreadable output, a missing file) leaves it FAILED with the failure's message, and
     * the job ends normally, so the worker moves on to the next task.
     */
    private suspend fun runTask(task: StoredTask) {
        val id = task.id
        // Refused when the task stopped being QUEUED after the worker picked it (a cancel won the race).
        if (!tasks.setRunningIfQueued(id, now())) return
        publish(id)
        try {
            chunkAudio(id)
            transcribeChunks(id, task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (tasks.failIfRunning(id, e.message ?: e.toString(), now())) publish(id)
            return
        }
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
        val source = workDirectories.source(id)
        // The original is the only copy of the audio until the plan is committed (ADR 0024).
        check(withContext(Dispatchers.IO) { Files.exists(source) }) {
            "the task's original audio is missing at $source"
        }
        val chunked = chunker.chunk(source, workDirectories.workDir(id))
        tasks.setDurationMs(id, chunked.durationMs)
        chunks.storePlan(id, chunked.chunks)
        withContext(Dispatchers.IO) { Files.deleteIfExists(source) }
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
