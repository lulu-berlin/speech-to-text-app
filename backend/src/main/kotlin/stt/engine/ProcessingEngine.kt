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
import stt.db.MergeOutcome
import stt.db.QueueStateRepository
import stt.db.RenameOutcome
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
        MutableSharedFlow<Event>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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

    /**
     * The started task the worker is waiting to resume: a started PAUSED task blocks the worker, so this is
     * set while it is paused and cleared when it is picked again, letting the resumed task win over any
     * task queued ahead of it.
     */
    private var waitingOn: Long? = null

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
            cleanupWorkDirectories()
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
     * A new upload was committed as a QUEUED task ([id]): publishes the task and the queue, so clients add it
     * to their queue list (item 13's contract: a `task-updated` for an unknown id is a new task), then wakes
     * the worker. Published before the wake, so the QUEUED update always precedes the worker's RUNNING one.
     */
    suspend fun taskCreated(id: Long) {
        publish(id)
        queueUpdated()
        wake()
    }

    /**
     * Pauses the queue: no further task starts until [resumeQueue], and the process of a task already
     * running is frozen (SIGSTOP; a no-op when no process is in flight).
     */
    suspend fun pauseQueue() {
        queueState.setPaused(true)
        processControl.pause()
        pauseGate.pause()
        queueUpdated()
    }

    /**
     * Resumes the queue and wakes the worker: the frozen process continues and the next queued task starts.
     */
    suspend fun resumeQueue() {
        queueState.setPaused(false)
        processControl.resume()
        pauseGate.resume()
        queueUpdated()
        wake()
    }

    /**
     * Writes a new queue order and publishes it. Item 12's `PUT /api/queue/order` checks the id set with
     * item 04's queue aggregate first and delegates here.
     */
    suspend fun reorderQueue(ids: List<Long>) {
        tasks.setQueuePositions(ids)
        queueUpdated()
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
            val runningHere = runningJob(id) != null
            tasks.setStatus(id, TaskStatus.QUEUED)
            publish(id)
            if (runningHere) {
                // A mid-run pause: the worker is inside this task, so it is RUNNING again at once.
                tasks.setStatus(id, TaskStatus.RUNNING)
                publish(id)
            } else {
                // A pause across a restart: there is no runner. The worker, which is waiting for this
                // task because a started PAUSED task blocks the queue, picks it up directly and moves it
                // to RUNNING itself, as for any task.
                wake()
            }
            processControl.resume()
            pauseGate.resume()
            TaskCommandOutcome.Accepted
        }

    /**
     * Deletes the task's row — its segments, speakers, chunks, chunk audio and metadata all cascade — and
     * only then publishes the deletion, so nothing referring to the id can re-appear after the event. Item
     * 12's `DELETE` cancels a pending task through the engine first and awaits that cancel before calling
     * this.
     */
    suspend fun deleteTask(id: Long): TaskCommandOutcome {
        val status = tasks.find(id)?.task?.status ?: return TaskCommandOutcome.Unknown
        // The item 12 contract: a pending task is cancelled through the engine first — its process is
        // stopped and the cancel has finished — then deleted. `cancelTask` guards the status itself, so a
        // cancel that races a completion loses and the row still goes.
        if (status in PENDING_DELETE) cancelTask(id)
        withContext(NonCancellable) {
            tasks.delete(id)
            deleteCachedAudio(id)
            _events.emit(Event.TaskDeleted(id))
        }
        queueUpdated()
        wake()
        return TaskCommandOutcome.Accepted
    }

    /**
     * Renames speaker [speakerId] of task [id] to [name] (item 22) and, only once the row is stored, publishes
     * the task's effective speakers as read back through its merges, so the event never shows a name that was
     * not stored, nor a speaker hidden by a merge. A refused rename ([RenameOutcome.DuplicateName]) stores and
     * publishes nothing.
     */
    suspend fun renameSpeaker(
        id: Long,
        speakerId: Int,
        name: String,
    ): RenameOutcome {
        val outcome = segments.renameSpeaker(id, speakerId, name)
        if (outcome == RenameOutcome.Renamed) _events.emit(Event.SpeakersRenamed(id, segments.findDiarized(id).speakers))
        return outcome
    }

    /**
     * Folds the speakers of [group] into [into] for task [id] (item 22) and, only once the mapping is stored,
     * publishes the task's effective speakers as read back through the merge mapping. A refused merge
     * ([MergeOutcome.Invalid]) stores and publishes nothing.
     */
    suspend fun mergeSpeakers(
        id: Long,
        group: List<Int>,
        into: Int,
    ): MergeOutcome {
        val outcome = segments.mergeSpeakers(id, group, into)
        if (outcome == MergeOutcome.Merged) _events.emit(Event.SpeakersMerged(id, segments.findDiarized(id).speakers))
        return outcome
    }

    /**
     * Undoes every merge of task [id] (item 22) and, once the mapping is gone, publishes the task's speakers — all
     * of them again — as `speakers-merged`, since its turns change as a merge's do.
     */
    suspend fun resetMerges(id: Long) {
        segments.resetMerges(id)
        _events.emit(Event.SpeakersMerged(id, segments.findDiarized(id).speakers))
    }

    /**
     * Removes the task's cached joined FLAC (`$STT_DATA_DIR/tmp/<id>.flac`, item 12's `/audio` cache), best
     * effort: the file lives outside the database, and a failure to remove it only wastes disk until the next
     * startup empties `tmp/` (the spec's cache lifecycle).
     */
    private suspend fun deleteCachedAudio(id: Long) =
        deleteFileBestEffort(workDirectories.cachedAudio(id), "cached audio of task $id", "the next startup empties tmp/")

    /**
     * Retries a FAILED or CANCELED task: it is queued at the end with its error cleared and runs again
     * like after a restart — the stored plan is reused, DONE chunks are skipped, the interrupted chunk is
     * rerun and diarization replaces the stored speakers (item 09). A COMPLETED task cannot be retried
     * (item 04's table has no way out of it).
     */
    suspend fun retryTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.QUEUED) {
            if (tasks.retryIfPossible(id)) {
                publish(id)
                queueUpdated()
                wake()
                TaskCommandOutcome.Accepted
            } else {
                val after = tasks.find(id)?.task?.status ?: return@command TaskCommandOutcome.Unknown
                TaskCommandOutcome.Illegal(after, TaskStatus.QUEUED)
            }
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
                if (canceled) queueUpdated()
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
     * The task to run next. Nothing while the queue is paused; and while a started task is paused, nothing
     * else either (a started PAUSED task is the only task that may resume, so the worker waits for it
     * instead of competing with it). Otherwise the QUEUED task with the lowest `queue_position` — or the
     * just-resumed task first, even when another task is waiting ahead of it in the queue.
     */
    private suspend fun nextTask(): StoredTask? {
        if (queueState.isPaused()) return null
        val paused = tasks.startedPaused()
        if (paused != null) {
            waitingOn = paused.id
            return null
        }
        val resumed = waitingOn?.let { id -> tasks.find(id)?.takeIf { it.task.status == TaskStatus.QUEUED } }
        waitingOn = null
        return resumed ?: tasks.nextQueued()
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

    /** Publishes the queue as it is now; call only after a change that affects it has been persisted. */
    private suspend fun queueUpdated() {
        _events.emit(Event.QueueUpdated(paused = queueState.isPaused(), taskIds = tasks.pendingOrderedIds()))
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
        // The work directory is scratch; a task whose terminal status (item 15) cleaned it up must get it
        // back before it writes chunk copies and decoded PCM again.
        withContext(Dispatchers.IO) { Files.createDirectories(workDirectories.workDir(id)) }
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
            if (failed) queueUpdated()
            return
        }
        tasks.setCompleted(id, now())
        removeCompletedWorkDir(id)
        publish(id)
        queueUpdated()
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
    private suspend fun deleteLeftoverOriginal(source: Path) =
        deleteFileBestEffort(source, "the leftover original", "the startup cleanup removes it")

    /**
     * Removes [file] best effort: a failure is logged with [what] and [consequence] and never propagates,
     * so a leftover file can never turn a task's success path into an error.
     */
    private suspend fun deleteFileBestEffort(
        file: Path,
        what: String,
        consequence: String,
    ) {
        try {
            withContext(Dispatchers.IO) { Files.deleteIfExists(file) }
        } catch (e: Exception) {
            logger.warn("Could not delete {} {}; {}: {}", file, what, consequence, e.toString())
        }
    }

    /**
     * Startup sweep (item 15): the work directory of every pending task is kept (an unchunked one holds
     * the only copy of its original); a FAILED or CANCELED task keeps only an unchunked original, for a
     * retry; COMPLETED, deleted and leftover directories are removed. Each directory is best effort, so
     * one failure never skips the rest.
     */
    private suspend fun cleanupWorkDirectories() {
        val root = workDirectories.workRoot()
        val dirs =
            withContext(Dispatchers.IO) {
                if (Files.isDirectory(root)) Files.list(root).use { it.toList() } else emptyList()
            }
        dirs.forEach { dir -> cleanupTaskDirectory(dir) }
    }

    /** Applies the sweep's rule to one work directory, named by its task's id. */
    private suspend fun cleanupTaskDirectory(dir: Path) {
        val id = dir.fileName.toString().toLongOrNull() ?: return
        try {
            val task = tasks.find(id)?.task
            if (task == null) {
                deleteTree(dir)
            } else {
                when (task.status) {
                    TaskStatus.COMPLETED -> {
                        deleteTree(dir)
                    }

                    TaskStatus.FAILED, TaskStatus.CANCELED -> {
                        if (chunks.findByTask(id).isEmpty()) pruneWorkDir(id) else deleteTree(dir)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not clean up the work directory {}; skipped: {}", dir, e.toString())
        }
    }

    /** Removes [dir] and everything in it, including [dir] itself. */
    private suspend fun deleteTree(dir: Path) {
        withContext(Dispatchers.IO) {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** Removes the task's work directory now that it is COMPLETED; best effort, the startup sweep is the backstop. */
    private suspend fun removeCompletedWorkDir(id: Long) {
        val dir = workDirectories.workDir(id)
        try {
            deleteTree(dir)
        } catch (e: Exception) {
            logger.warn("Could not remove the completed task's work directory {}: {}", dir, e.toString())
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

/** The statuses whose deletion must cancel the task through the engine first (item 12's contract). */
private val PENDING_DELETE = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED)

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
