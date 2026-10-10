package stt.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.audio.Chunker
import stt.audio.PcmDecoder
import stt.config.Config
import stt.db.Chunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.DiarizationStage
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.StoredTask
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.pause.PauseGate
import stt.process.ProcessControl
import stt.queue.TaskStatus
import stt.transcribe.ChunkTranscription
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSettings
import stt.transcribe.resumePrompt
import stt.transcribe.taskProgress
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
class ProcessingEngine internal constructor(
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
    private val diarizer: Diarizer,
    private val pcmDecoder: PcmDecoder,
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

    /**
     * The in-JVM pause gate (ADR 0004): the DIARIZING phase checks it, and [pauseTask]/[pauseQueue]
     * close it, so a paused task without an external process still stops at the next checkpoint.
     * One gate suffices: the worker runs one task at a time.
     */
    private val pauseGate = PauseGate()

    /** The task whose phases are running and the job running them, so [cancelTask] can kill its process. */
    private class Running(
        val id: Long,
        val job: Job,
    )

    private val runningLock = Mutex()
    private var running: Running? = null

    /**
     * Starts the single worker coroutine; it runs until [scope] is cancelled. Before it picks anything up,
     * tasks whose crash left them RUNNING are queued again (ADR 0004), so the worker resumes them from
     * their stored plans instead of leaving them stuck.
     */
    fun start() {
        scope.launch {
            tasks.recoverRunning()
            workerLoop()
        }
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
        pauseGate.pause()
    }

    /**
     * Resumes the queue and wakes the worker: the frozen process continues and the next queued task starts.
     */
    suspend fun resumeQueue() {
        queueState.setPaused(false)
        processControl.resume()
        pauseGate.resume()
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
            pauseGate.pause()
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
            pauseGate.resume()
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
                val canceled = tasks.cancelIfPossible(id, now())
                pruneWorkDir(id)
                if (canceled) publish(id)
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
            diarize(id, task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = tasks.failIfRunning(id, e.message ?: e.toString(), now())
            pruneWorkDir(id)
            if (failed) publish(id)
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
        // A stored plan means a previous run chunked this task: reuse the stored chunks (ADR 0004); a crash
        // between the plan's commit and the original's deletion can still have left the original behind,
        // and it is redundant now. Only first-time chunking needs it, as the only copy of the audio until
        // the plan is committed (ADR 0024).
        if (chunks.findByTask(id).isNotEmpty()) {
            deleteLeftoverOriginal(source)
            return
        }
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
        val stored = chunks.findByTask(id)
        val workDir = workDirectories.workDir(id)
        // whisper's -pp lines arrive on a non-suspending callback, so the chunks run in a child coroutine
        // and report over a channel, which this coroutine drains, persists and publishes in order.
        val reports = Channel<TranscriptionReport?>(Channel.UNLIMITED)
        coroutineScope {
            launch {
                for (chunk in stored) {
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
                        onProgress = { fraction -> reports.trySend(TranscriptionReport(chunk.idx, fraction)) },
                    )
                }
                // The null report is the terminator: everything before it has been sent.
                reports.trySend(null)
            }
            var doneIdx = -1
            while (true) {
                val report = reports.receive() ?: break
                // The child transcribes chunks in order, so a report for a higher chunk means every chunk
                // before it is DONE; item 07's weighted progress is evaluated against that knowledge.
                if (report.chunkIdx > doneIdx) doneIdx = report.chunkIdx
                val chunks =
                    stored.map { chunk -> if (chunk.idx < doneIdx) chunk.copy(status = ChunkStatus.DONE) else chunk }
                transcriptionProgress(id, chunks, report.chunkIdx, report.fraction)
            }
        }
    }

    /** Persists the weighted transcription progress and then publishes it, so the database leads the event. */
    private suspend fun transcriptionProgress(
        id: Long,
        chunks: List<Chunk>,
        runningIdx: Int,
        fraction: Double,
    ) {
        tasks.setProgress(id, taskProgress(chunks, runningIdx, fraction))
        val task = tasks.find(id) ?: return
        _events.emit(
            Event.TaskProgress(
                id = task.id,
                transcriptionProgress = task.task.progress,
                diarizationStage = task.task.diarizationStage,
                diarizationProgress = task.task.diarizationProgress,
            ),
        )
    }

    /**
     * DIARIZING: skipped when the speakers are off (`speakers_mode = off` asks for no labels) and when the
     * task has no segments (there is nothing to label; item 09 skips it then too). Otherwise the whole-file
     * PCM is rebuilt from the stored chunks (ADR 0024), and the [Diarizer] assigns and stores the speakers.
     */
    private suspend fun diarize(
        id: Long,
        task: StoredTask,
    ) {
        if (task.task.speakersMode == SpeakersMode.OFF || segments.findByTask(id).isEmpty()) return
        tasks.setPhase(id, TaskPhase.DIARIZING)
        publish(id)
        // The contract (item 12): progress is 0 before diarization starts, 1 once the speakers are stored,
        // with no stage left over. The reports in between are each persisted and then published.
        diarizationProgress(id, null, 0.0)
        val pcm = rebuildPcm(id)
        val reports = Channel<DiarizationReport?>(Channel.UNLIMITED)
        coroutineScope {
            launch {
                val stage = MutableStateFlow<DiarizationStage?>(null)
                diarizer.diarize(
                    task,
                    pauseGate,
                    pcm,
                    onStage = { stage.value = it },
                    onProgress = { value -> reports.trySend(DiarizationReport(stage.value, value)) },
                )
                // The null report is the terminator: everything before it has been sent.
                reports.trySend(null)
            }
            while (true) {
                val report = reports.receive() ?: break
                diarizationProgress(id, report.stage, report.value)
            }
        }
        diarizationProgress(id, null, 1.0)
    }

    /**
     * Rebuilds the task's whole-file PCM from its stored chunk FLACs, each decoded and concatenated in
     * `idx` order (ADR 0024). Only the stored chunks are read, so a restart changes nothing; the work
     * directory is scratch.
     */
    private suspend fun rebuildPcm(id: Long): FloatArray {
        val workDir = workDirectories.workDir(id)
        val pcm = ByteArrayOutputStream()
        for (chunk in chunks.findByTask(id)) {
            val flac = workDir.resolve("chunk-${chunk.idx}.flac")
            writeChunkFlac(id, chunk.idx, flac)
            val decoded = workDir.resolve("chunk-${chunk.idx}.f32")
            pcmDecoder.decode(flac, decoded)
            withContext(Dispatchers.IO) { pcm.writeBytes(Files.readAllBytes(decoded)) }
        }
        val bytes = pcm.toByteArray()
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()) { floats.get() }
    }

    /** Persists a diarization stage report and then publishes it, so the database leads the event. */
    private suspend fun diarizationProgress(
        id: Long,
        stage: DiarizationStage?,
        value: Double,
    ) {
        tasks.setDiarizationProgress(id, stage, value)
        val task = tasks.find(id) ?: return
        _events.emit(
            Event.TaskProgress(
                id = task.id,
                transcriptionProgress = task.task.progress,
                diarizationStage = task.task.diarizationStage,
                diarizationProgress = task.task.diarizationProgress,
            ),
        )
    }

    /** Deletes a leftover original of a task whose plan is already stored; best effort, as it changes nothing. */
    private suspend fun deleteLeftoverOriginal(source: Path) {
        try {
            withContext(Dispatchers.IO) { Files.deleteIfExists(source) }
        } catch (e: Exception) {
            logger.warn("Could not delete the leftover original {}; the startup cleanup removes it: {}", source, e.toString())
        }
    }

    /**
     * Removes everything in the task's work directory except its original (ADR 0024): the chunker's decoded
     * PCM and exported chunks, and the copies written for whisper-cli. A task that was never chunked keeps
     * its original, which for a FAILED or CANCELED task is the only copy of the audio (a retry needs it); a
     * chunked task has no original left. Best effort: a leftover file is logged and removed by the startup
     * cleanup instead, so it cannot turn a finished cancel or failure into an error.
     */
    private suspend fun pruneWorkDir(id: Long) {
        val workDir = workDirectories.workDir(id)
        val source = workDirectories.source(id)
        try {
            withContext(Dispatchers.IO) {
                if (!Files.isDirectory(workDir)) return@withContext
                Files.walk(workDir).use { paths ->
                    paths
                        .filter { it != workDir && it != source }
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not prune the work directory {}; the startup cleanup removes it: {}", workDir, e.toString())
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ProcessingEngine::class.java)
    }
}

/** One stage report of the DIARIZING phase, with the stage snapshotted as the diarizer sent it. */
private data class DiarizationReport(
    val stage: DiarizationStage?,
    val value: Double,
)

/** One whisper-cli progress report: the chunk it belongs to and the chunk fraction, as parsed. */
private data class TranscriptionReport(
    val chunkIdx: Int,
    val fraction: Double,
)
