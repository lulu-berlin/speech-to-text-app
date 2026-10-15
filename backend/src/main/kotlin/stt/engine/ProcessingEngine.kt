package stt.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
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
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import stt.audio.Chunker
import stt.audio.PcmDecoder
import stt.audio.SAMPLES_PER_MS
import stt.audio.WindowCutter
import stt.audio.toCanonicalRows
import stt.config.Config
import stt.config.Device
import stt.config.dbValue
import stt.db.AudioHeaderRepository
import stt.db.AudioRepository
import stt.db.AudioSecondRepository
import stt.db.CanonicalClipRepository
import stt.db.Chunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.DiarizationStage
import stt.db.MergeOutcome
import stt.db.QueueStateRepository
import stt.db.RenameOutcome
import stt.db.ScratchDir
import stt.db.SegmentRepository
import stt.db.Settings
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.db.StoredTask
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.pause.PauseGate
import stt.process.ProcessControl
import stt.queue.TaskStatus
import stt.transcribe.ChunkTranscription
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSettings
import stt.transcribe.resumePrompt
import stt.transcribe.taskProgress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    private val audio: AudioRepository,
    private val audioHeaders: AudioHeaderRepository,
    private val audioSeconds: AudioSecondRepository,
    private val clipAudio: CanonicalClipRepository,
    private val segments: SegmentRepository,
    private val queueState: QueueStateRepository,
    /** Read once per task as it starts (item 32): its device and thread count hold for the whole run. */
    private val settings: SettingsRepository,
    private val scratchDir: ScratchDir,
    private val chunker: Chunker,
    private val transcriber: Transcriber,
    private val processControl: ProcessControl,
    private val diarizer: Diarizer,
    private val pcmDecoder: PcmDecoder,
    private val windowCutter: WindowCutter,
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
     * The in-JVM pause gate (ADR 0004): the DIARIZING phase and the chunk loop (before each whisper-cli run)
     * check it, and [pauseTask]/[pauseQueue] close it, so a paused task stops at the next checkpoint even
     * when no external process is in flight to freeze. One gate suffices: the worker runs one task at a
     * time, and every run starts with it open ([runInJob]).
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
     * Starts the single worker coroutine, named `engine-worker` (item 46); it runs until [scope] is cancelled.
     * Before it picks anything up, tasks whose crash left them RUNNING are queued again (ADR 0004), so the
     * worker resumes them from their stored plans instead of leaving them stuck.
     */
    fun start() {
        scope.launch(CoroutineName("engine-worker")) {
            tasks.recoverRunning()
            scratchDir.clear()
            workerLoop()
        }
    }

    /**
     * Tells the worker that the queue may have changed — a resume, a retry, a reorder, a queue resume, a new
     * upload through [taskCreated] — so it looks again instead of waiting. Callers do not need to be precise:
     * the signal is conflated, so repeating it is free and a signal sent while the worker is busy survives
     * until it next checks the queue.
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
     * The settings were stored (item 32, `PUT /api/settings`): publishes them, so open tabs follow.
     */
    suspend fun settingsUpdated(settings: Settings) {
        _events.emit(Event.SettingsUpdated(settings))
    }

    /**
     * Pauses the queue: no further task starts until [resumeQueue], and the process of a task already
     * running is frozen (SIGSTOP; a no-op when no process is in flight).
     */
    suspend fun pauseQueue() {
        queueState.setPaused(true)
        freezeCurrentRun()
        queueUpdated()
    }

    /**
     * Resumes the queue and wakes the worker: the frozen process continues and the next queued task starts.
     */
    suspend fun resumeQueue() {
        queueState.setPaused(false)
        continueCurrentRun()
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
     * Pauses a task: its status becomes PAUSED and, when it is the task the worker is running, its process
     * is frozen and the pause gate closed; the phase it is in stays, so a paused task can still say where
     * it stopped. A task the worker is not running (QUEUED, never started) only changes status: the
     * process in flight and the pause gate belong to the *current* run, which is another task's. Only a
     * QUEUED or RUNNING task can be paused (item 04's table). The write is conditional: a pause that races
     * a task's finish is refused instead of writing PAUSED over the newer status, and the losing pause
     * freezes nothing — the process and the pause gate are only touched when the pause won (frozen
     * processes and a paused gate would stall the *next* task).
     */
    suspend fun pauseTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.PAUSED) {
            val runningHere = runningJob(id) != null
            if (tasks.pauseIfPossible(id)) {
                publish(id)
                if (runningHere) {
                    freezeCurrentRun()
                }
                TaskCommandOutcome.Accepted
            } else {
                lostTransition(id, TaskStatus.PAUSED)
            }
        }

    /**
     * Resumes a paused task at once: item 04's table has no `PAUSED → RUNNING`, so a task with no runner
     * is written back as QUEUED and the worker picks it up directly, continuing where it stopped even
     * when another QUEUED task is ahead of it; a task whose worker is still inside it is written RUNNING
     * again at once, and only then is its process continued and the pause gate opened. Those belong to
     * the *current* run: resuming a task the worker is not inside (paused while queued, or across a
     * restart) continues nothing, so it cannot thaw another task that is paused in place. Either way it
     * is one conditional write, so a resume that races a cancel, a failure or a completion is refused and
     * unfreezes nothing. Neither write touches the phase: only the queue-level QUEUED (retry, restart
     * recovery) drops it, so a resume cannot lose a stopped task's phase.
     */
    suspend fun resumeTask(id: Long): TaskCommandOutcome =
        command(id, TaskStatus.QUEUED) {
            val runningHere = runningJob(id) != null
            if (tasks.resumeIfPaused(id, if (runningHere) TaskStatus.RUNNING else TaskStatus.QUEUED)) {
                publish(id)
                if (runningHere) {
                    continueCurrentRun()
                } else {
                    // No runner: the task was paused while queued, or across a restart. The worker picks it
                    // up when it looks again (a started PAUSED task it waits on, directly) and moves it to
                    // RUNNING itself, as for any task.
                    wake()
                }
                TaskCommandOutcome.Accepted
            } else {
                lostTransition(id, TaskStatus.QUEUED)
            }
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
            removeScratch(id)
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
                lostTransition(id, TaskStatus.QUEUED)
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
                removeScratch(id)
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
     * The outcome of a command whose conditional write was refused: the transition lost to a newer state,
     * so it reports [TaskCommandOutcome.Illegal] with the status the task is actually in — or Unknown
     * when the row is gone meanwhile. Shared by the commands whose write re-checks the status (pause,
     * resume, retry); [cancelTask] decides Accepted vs Illegal for itself because its write is the
     * terminal state it reports.
     */
    private suspend fun lostTransition(
        id: Long,
        to: TaskStatus,
    ): TaskCommandOutcome =
        tasks
            .find(id)
            ?.task
            ?.status
            ?.let { TaskCommandOutcome.Illegal(it, to) }
            ?: TaskCommandOutcome.Unknown

    /** Freezes the current run: its process (SIGSTOP; a no-op when none is in flight) and its pause gate. */
    private fun freezeCurrentRun() {
        processControl.pause()
        pauseGate.pause()
    }

    /** Continues the current run: its frozen process and its pause gate. */
    private fun continueCurrentRun() {
        processControl.resume()
        pauseGate.resume()
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
     * cancelling the coroutine inside item 05's `run` is what kills the task's process (ADR 0020). It is
     * named `task-<id>`, so the coroutine debugger and dumps show which task it runs (item 46).
     *
     * The run starts with the pause gate open: a pause whose task finished anyway (completion wins over a
     * pause) must not hold the next task's DIARIZING phase. The gate opens before the run is registered
     * in [running], so a [pauseTask] that sees this task running closes it after the reset, never before.
     * A paused queue is honoured before this: [nextTask] starts nothing while the queue is paused.
     */
    private suspend fun runInJob(task: StoredTask) {
        pauseGate.resume()
        val job =
            scope.launch(CoroutineName("task-${task.id}")) {
                withTaskLoggingContext(task.id) {
                    runTask(task)
                }
            }
        runningLock.withLock { running = Running(task.id, job) }
        try {
            job.join()
        } finally {
            runningLock.withLock { running = null }
        }
    }

    /**
     * Runs [block] with the task's id in the logging context (item 46): `MDCContext` carries the caller's map
     * plus the id, and the library applies it around every resumption, across dispatcher switches, and every
     * child coroutine inherits it, so the chunk loop, IO blocks, progress readers and the failure path all log
     * with the id. The thread's own map is never written: on every suspension the library restores the map
     * the thread had, so the id does not leak onto the thread for the coroutines that run on it next.
     */
    private suspend fun <T> withTaskLoggingContext(
        id: Long,
        block: suspend () -> T,
    ): T = withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + (TASK_ID_MDC_KEY to id.toString()))) { block() }

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
        // The settings as the task starts (item 32): every chunk and the diarization of this run use them, so a
        // change applies from the next task the engine starts and a running (or paused) task keeps its device.
        val run = settings.get()
        publish(id)
        try {
            chunkAudio(id)
            transcribeChunks(id, task, run)
            diarize(id, task, run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = tasks.failIfRunning(id, e.message ?: e.toString(), now())
            removeScratch(id)
            if (failed) publish(id)
            if (failed) queueUpdated()
            return
        }
        tasks.setCompleted(id, now())
        removeScratch(id)
        publish(id)
        queueUpdated()
    }

    /**
     * CHUNKING (ADR 0024): decode, plan and export the task's chunks into its scratch directory, store the
     * decoded duration, then the plan with every chunk FLAC and the canonical clip in one transaction, and only
     * then delete the original from the database, which after that commit is no longer the only copy of the
     * audio. The written-out original and the chunker's scratch exist only for this phase (item 44): they are
     * removed on every path — success, failure, cancel — not left for the rest of the task's run.
     */
    private suspend fun chunkAudio(id: Long) {
        tasks.setPhase(id, TaskPhase.CHUNKING)
        publish(id)
        // A stored plan with its whole clip (header and rows) means a previous run chunked this task: they are
        // reused (ADR 0004), and the original, redundant now, is dropped if it is still there. Anything less is
        // what an interrupted run of an earlier version left: the task is chunked again from its original.
        if (chunks.findByTask(id).isNotEmpty() && audioHeaders.read(id) != null && audioSeconds.count(id) > 0) {
            audio.deleteOriginal(id)
            return
        }
        val source = scratchDir.originalFile(id)
        val chunkerDir = scratchDir.chunkerDir(id)
        try {
            writeOriginal(id, source)
            val chunked = chunker.chunk(source, chunkerDir)
            tasks.setDurationMs(id, chunked.durationMs)
            chunks.storeChunked(id, chunked.chunks, chunked.canonical.header, chunked.canonical.toCanonicalRows())
            // The canonical clip is now the task's stored audio: the original upload is no longer needed.
            audio.deleteOriginal(id)
        } finally {
            withContext(NonCancellable) {
                deleteTree(chunkerDir)
                withContext(Dispatchers.IO) { Files.deleteIfExists(source) }
            }
        }
    }

    /**
     * Writes the task's original upload from the database to [source] for the chunker (item 44). It is written under
     * a temporary name and moved into place, so a failed write never leaves a truncated original behind. Fails
     * naming [source] when the database holds no original.
     */
    private suspend fun writeOriginal(
        id: Long,
        source: Path,
    ) {
        withContext(Dispatchers.IO) {
            val partial = source.resolveSibling("${source.fileName}.part")
            try {
                Files.newOutputStream(partial).use { audio.copyOriginalTo(id, it) }
                Files.move(partial, source, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: NoSuchElementException) {
                throw IllegalStateException("the task's original audio is missing at $source", e)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
    }

    /**
     * TRANSCRIBING (ADR 0004): each chunk in `idx` order that is not DONE yet, with the previous chunk's
     * stored text as the prompt. The chunk's input is cut from the stored canonical clip for whisper-cli and
     * removed again once the chunk is stored (items 07, 44). Each chunk first passes the pause gate: a pause that
     * missed the previous chunk's exiting process (a freeze only reaches a process in flight) still stops the task
     * before its next whisper-cli run, until it is resumed.
     */
    private suspend fun transcribeChunks(
        id: Long,
        task: StoredTask,
        run: Settings,
    ) {
        tasks.setPhase(id, TaskPhase.TRANSCRIBING)
        publish(id)
        val stored = chunks.findByTask(id)
        // whisper's -pp lines arrive on a non-suspending callback, so the chunks run in a child coroutine
        // and report over a channel, which this coroutine drains, persists and publishes in order.
        val reports = Channel<TranscriptionReport?>(Channel.UNLIMITED)
        // The run's settings; they switch to the CPU if whisper-cli reports the chosen GPU unusable (item 32).
        var current = run
        coroutineScope {
            launch {
                // The whisper inputs are transient files in the app's scratch directory (item 44), one per
                // chunk; the task's directory is created once for the whole run.
                val scratch = scratchDir.taskDir(id)
                for (chunk in stored) {
                    if (chunk.status == ChunkStatus.DONE) continue
                    pauseGate.checkpoint()
                    val audio = scratch.resolve("chunk-${chunk.idx}.flac")
                    writeChunkInput(id, chunk, audio)
                    transcription.transcribe(
                        taskId = id,
                        chunk = chunk,
                        settings = settingsFor(task, current),
                        audio = audio,
                        outputPrefix = scratch.resolve("chunk-${chunk.idx}"),
                        prompt = resumePrompt(segments, id, chunk.idx),
                        onProgress = { fraction -> reports.trySend(TranscriptionReport(chunk.idx, fraction)) },
                        onGpuUnavailable = { current = fallBackToCpu(id, current) },
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
        run: Settings,
    ) {
        if (task.task.speakersMode == SpeakersMode.OFF || segments.findByTask(id).isEmpty()) return
        tasks.setPhase(id, TaskPhase.DIARIZING)
        publish(id)
        // The contract (item 12): progress is 0 before diarization starts, 1 once the speakers are stored,
        // with no stage left over. The reports in between are each persisted and then published.
        diarizationProgress(id, null, 0.0)
        val pcm = clipPcm(id)
        val reports = Channel<DiarizationReport?>(Channel.UNLIMITED)
        coroutineScope {
            launch {
                val stage = MutableStateFlow<DiarizationStage?>(null)
                diarizer.diarize(
                    task,
                    pauseGate,
                    pcm,
                    run.threads,
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
     * The task's whole-file PCM, decoded from its stored canonical clip (item 44): the clip is materialized
     * from the database to one transient scratch file, decoded to a second, and both are removed on every
     * path. No chunk FLAC is read, so a task whose per-chunk copies are gone still diarizes, and a restart
     * decodes the same stored bytes.
     */
    private suspend fun clipPcm(id: Long): FloatArray {
        val clip = scratchDir.clipFile(id)
        val decoded = scratchDir.clipPcm(id)
        try {
            withContext(Dispatchers.IO) {
                Files.newOutputStream(clip).use { out -> clipAudio.copyTo(id, 0, clipAudio.size(id), out) }
            }
            pcmDecoder.decode(clip, decoded)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(decoded) }
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(floats.remaining()) { floats.get() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(clip)
                Files.deleteIfExists(decoded)
            }
        }
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

    /** Removes [dir] and everything in it, including [dir] itself; a directory that is not there is left alone. */
    private suspend fun deleteTree(dir: Path) {
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(dir)) return@withContext
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * Removes the task's scratch directory — the chunker's scratch, the materialized original, the whisper
     * inputs and any decoded clip — once the task has ended: COMPLETED, FAILED, CANCELED or deleted. Best
     * effort, like every cleanup here: a failure only leaves files the startup [ScratchDir.clear] sweeps.
     */
    private suspend fun removeScratch(id: Long) {
        val dir = scratchDir.taskDirPath(id)
        try {
            deleteTree(dir)
        } catch (e: Exception) {
            logger.warn("Could not remove task {}'s scratch directory {}: {}", id, dir, e.toString())
        }
    }

    /**
     * Writes [chunk]'s audio to [target], where whisper-cli reads it (item 44): the clip's header and the one-second
     * rows the chunk's window touches go to a transient window file, and [windowCutter] cuts the chunk's samples
     * from it. The window file is removed on every path.
     */
    private suspend fun writeChunkInput(
        id: Long,
        chunk: Chunk,
        target: Path,
    ) {
        val firstSecond = (chunk.startMs / 1_000).toInt()
        val endSecond = ((chunk.endMs + 999) / 1_000).toInt()
        val offsetMs = firstSecond * 1_000L
        val window = target.resolveSibling("${target.fileName}.window")
        try {
            withContext(Dispatchers.IO) {
                Files.newOutputStream(window).use { out -> clipAudio.copySecondsTo(id, firstSecond, endSecond, out) }
            }
            windowCutter.cut(
                window,
                fromSample = (chunk.startMs - offsetMs) * SAMPLES_PER_MS,
                toSample = (chunk.endMs - offsetMs) * SAMPLES_PER_MS,
                target = target,
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(window) }
        }
    }

    /**
     * whisper-cli could not use the GPU [run] chose and ran task [id] on the CPU (item 32): stores the device as CPU
     * with a notice naming the GPU, logs and publishes it, and returns the settings for the rest of the run. A run
     * that chose no GPU has nothing to fall back from and keeps its settings.
     */
    private suspend fun fallBackToCpu(
        id: Long,
        run: Settings,
    ): Settings {
        val gpu = run.device as? Device.Gpu ?: return run
        logger.warn("Task {}: {} is unavailable, it ran on the CPU; the device is now CPU", id, gpu.dbValue())
        _events.emit(Event.SettingsUpdated(settings.fallBackToCpu(gpu)))
        return run.copy(device = Device.Cpu)
    }

    /** Persists the change, then publishes the task as it now is. */
    private suspend fun publish(id: Long) {
        tasks.find(id)?.let { _events.emit(Event.TaskUpdated(it)) }
    }

    /** whisper-cli's settings for one run of [task]: its language, and the device and threads of the [run]'s settings. */
    private fun settingsFor(
        task: StoredTask,
        run: Settings,
    ) = WhisperSettings(
        model = config.whisperModel,
        language = task.task.language,
        threads = run.threads,
        device = run.device,
    )

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** The MDC key the task's id is logged under (item 46; `logback.xml`'s console pattern reads the same key). */
        const val TASK_ID_MDC_KEY = "taskId"

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
