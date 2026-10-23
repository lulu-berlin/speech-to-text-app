package stt.engine

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import stt.audio.PlannedChunk
import stt.audio.toCanonicalRows
import stt.config.testConfig
import stt.db.AudioHeaderRepository
import stt.db.AudioRepository
import stt.db.AudioSecondRepository
import stt.db.CanonicalClipRepository
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.ScratchDir
import stt.db.SegmentRepository
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.db.Task
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TestDataDir
import stt.db.insertTaskRow
import stt.db.queuedTask
import stt.process.ProcessPlatform
import stt.queue.TaskStatus
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One engine and everything a spec drives around it (fake chunker and transcriber, repositories, events). */
internal class EngineFixture(
    val scratchDir: ScratchDir,
    val tasks: TaskRepository,
    val chunker: FakeChunker,
    val transcriber: FakeTranscriber,
    val processControl: FakeProcessControl,
    val diarizer: FakeDiarizer,
    val pcmDecoder: FakePcmDecoder,
    val windowCutter: FakeWindowCutter,
    val engine: ProcessingEngine,
    /** The ids of the tasks that reach COMPLETED, in order. */
    val completed: ReceiveChannel<Long>,
    /** The ids of the tasks that reach FAILED, in order. */
    val failed: ReceiveChannel<Long>,
    /** The task-progress events, in order. */
    val progress: ReceiveChannel<Event.TaskProgress>,
    /** Every event, in order, unforked. */
    val events: ReceiveChannel<Event>,
)

/**
 * Builds the engine under test against [db] with the fakes and a channel of COMPLETED task ids. The
 * worker is not started yet, so a spec enqueues first or starts on an empty queue. The subscription is
 * started undispatched, so it is in place before the caller can start the worker and no event is missed.
 * The engine runs in [scope]; a spec that quits the app, as `EngineModule`'s cleanup does, passes a scope of
 * its own to cancel.
 */
internal fun TestScope.engineUnderTest(
    dataDir: TestDataDir,
    db: Database,
    scope: CoroutineScope = backgroundScope,
): EngineFixture {
    val scratchDir = ScratchDir(dataDir.dataDir)
    val tasks = TaskRepository(db)
    val chunker = FakeChunker()
    val transcriber = FakeTranscriber()
    val processControl = FakeProcessControl()
    val diarizer = FakeDiarizer()
    val pcmDecoder = FakePcmDecoder()
    val windowCutter = FakeWindowCutter()
    val engine =
        ProcessingEngine(
            scope = scope,
            config = testConfig(dataDir.dataDir),
            tasks = tasks,
            chunks = ChunkRepository(db),
            audio = AudioRepository(db),
            audioHeaders = AudioHeaderRepository(db),
            audioSeconds = AudioSecondRepository(db),
            clipAudio = CanonicalClipRepository(db),
            segments = SegmentRepository(db),
            queueState = QueueStateRepository(db),
            settings = SettingsRepository(db),
            scratchDir = scratchDir,
            chunker = chunker,
            transcriber = transcriber,
            processControl = processControl,
            diarizer = diarizer,
            pcmDecoder = pcmDecoder,
            windowCutter = windowCutter,
        )
    val completed = Channel<Long>(Channel.UNLIMITED)
    val failed = Channel<Long>(Channel.UNLIMITED)
    val progress = Channel<Event.TaskProgress>(Channel.UNLIMITED)
    val events = Channel<Event>(Channel.UNLIMITED)
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
            events.send(event)
            when (event) {
                is Event.TaskUpdated -> {
                    when (event.task.task.status) {
                        TaskStatus.COMPLETED -> completed.send(event.task.id)
                        TaskStatus.FAILED -> failed.send(event.task.id)
                        else -> Unit
                    }
                }

                is Event.TaskProgress -> {
                    progress.send(event)
                }

                else -> {
                    Unit
                }
            }
        }
    }
    return EngineFixture(
        scratchDir,
        tasks,
        chunker,
        transcriber,
        processControl,
        diarizer,
        pcmDecoder,
        windowCutter,
        engine,
        completed,
        failed,
        progress,
        events,
    )
}

/** Inserts a QUEUED task at [queuePosition] whose original is stored in the database, as an upload does. */
internal suspend fun enqueueTask(
    tasks: TaskRepository,
    filename: String,
    queuePosition: Int,
    createdAt: Long,
    speakersMode: SpeakersMode = SpeakersMode.OFF,
): Long =
    tasks.createFromUpload(
        queuedTask(filename, createdAt).copy(queuePosition = queuePosition, speakersMode = speakersMode),
        "fake source audio of $filename".toByteArray().inputStream(),
    )

/** The id of the task a scratch path belongs to: its nearest `<scratch>/<id>` ancestor, itself included (item 44). */
internal fun taskIdOf(path: Path): Long = generateSequence(path) { it.parent }.firstNotNullOf { it.fileName?.toString()?.toLongOrNull() }

/** The stored row of a task, which must exist. */
internal suspend fun storedTaskOf(
    fixture: EngineFixture,
    task: Long,
): Task =
    fixture.tasks
        .find(task)
        ?.task
        .shouldNotBeNull()

/**
 * The statuses [task]'s published updates went through, in order, each change once: its phase and progress publishes
 * repeat the status, so consecutive repeats are dropped. Drains [EngineFixture.events].
 */
internal fun statusesOf(
    fixture: EngineFixture,
    task: Long,
): List<TaskStatus> =
    drain(fixture.events)
        .filterIsInstance<Event.TaskUpdated>()
        .filter { it.task.id == task }
        .map { it.task.task.status }
        .fold(emptyList()) { seen, status -> if (seen.lastOrNull() == status) seen else seen + status }

/** The file name of the chunk the transcriber starts next, for a spec waiting for a known chunk to begin. */
internal suspend fun nextChunkEntered(transcriber: FakeTranscriber): String =
    transcriber.entered
        .receive()
        .fileName
        .toString()

/** The stored status of a task. */
internal suspend fun statusOf(
    fixture: EngineFixture,
    task: Long,
): TaskStatus? =
    fixture.tasks
        .find(task)
        ?.task
        ?.status

/** The stored phase of a task. */
internal suspend fun phaseOf(
    fixture: EngineFixture,
    task: Long,
): TaskPhase? =
    fixture.tasks
        .find(task)
        ?.task
        ?.phase

/** When a task finished, as stored; null while it has not. */
internal suspend fun finishedAtOf(
    fixture: EngineFixture,
    task: Long,
): Long? =
    fixture.tasks
        .find(task)
        ?.task
        ?.finishedAt

/**
 * Pumps the test dispatcher until [done] holds or [window] of real time has passed. A claim that something
 * has *not* happened cannot be awaited, so the test drives the worker itself: [runCurrent] runs it on the
 * test dispatcher, and each short real delay lets the file and database I/O in between finish.
 */
internal suspend fun TestScope.pumpUntil(
    window: Duration,
    done: suspend () -> Boolean,
) {
    val deadline = System.nanoTime() + window.inWholeNanoseconds
    runCurrent()
    while (System.nanoTime() < deadline && !done()) {
        withContext(Dispatchers.Default) { delay(2.milliseconds) }
        runCurrent()
    }
}

/** The tasks the engine has started after [window] of real time, returning early once a second one starts. */
internal suspend fun TestScope.tasksStartedWithin(
    fixture: EngineFixture,
    window: Duration,
): List<Long> {
    pumpUntil(window) { fixture.chunker.workDirs.size > 1 }
    return fixture.chunker.workDirs.map(::taskIdOf)
}

/** Whether [deferred] completes while [window] of real time passes, returning early once it does. */
internal suspend fun TestScope.completesWithin(
    deferred: Deferred<*>,
    window: Duration,
): Boolean {
    pumpUntil(window) { deferred.isCompleted }
    return deferred.isCompleted
}

/** Every element a channel currently holds, without waiting for more. */
internal fun <T> drain(channel: ReceiveChannel<T>): List<T> =
    buildList {
        var next = channel.tryReceive()
        while (next.isSuccess) {
            add(next.getOrThrow())
            next = channel.tryReceive()
        }
    }

/**
 * Completes [held] after [window] of real time, off the test scheduler: a hold that must outlast a
 * real-time window has to run on [Dispatchers.Default], where `runTest` cannot advance it.
 */
internal fun CoroutineScope.releaseAfter(
    window: Duration,
    held: CompletableDeferred<Unit>,
) {
    launch(Dispatchers.Default) {
        delay(window)
        held.complete(Unit)
    }
}

/**
 * Receives COMPLETED task ids until every id in [ids] has arrived and returns the ids it received;
 * duplicates and order are tolerated. [bound] is a safety bound, not a behavioural wait: a slow engine
 * never reaches it, and a stuck one fails with an [AssertionError] naming the ids that never arrived.
 * The bound runs on [Dispatchers.Default], so the test scheduler's virtual time cannot expire it.
 */
internal suspend fun awaitCompleted(
    completed: ReceiveChannel<Long>,
    ids: Set<Long>,
    bound: Duration = COMPLETION_SAFETY_BOUND,
): Set<Long> {
    val received = mutableSetOf<Long>()
    val finished =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(bound) {
                while (!received.containsAll(ids)) received += completed.receive()
                true
            }
        }
    if (finished == null) {
        throw AssertionError(
            "task(s) ${ids - received} did not complete within $bound; completed ids received: $received",
        )
    }
    return received
}

/** A plan of [chunkCount] consecutive one-second windows, as the fake chunker plans them. */
internal fun plannedWindows(chunkCount: Int): List<PlannedChunk> =
    (0 until chunkCount).map { idx -> PlannedChunk(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L) }

/**
 * Every regular file under [root], at any depth; none when [root] does not exist. A file or directory deleted while
 * the walk runs counts as gone: specs poll scratch trees that the engine is clearing at the same time.
 */
internal fun filesUnder(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    if (!Files.isDirectory(root)) return files
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (Files.isRegularFile(file)) files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = if (gone(file, exc)) FileVisitResult.CONTINUE else throw exc

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult = if (exc == null || gone(dir, exc)) FileVisitResult.CONTINUE else throw exc
        },
    )
    return files
}

/**
 * A path the concurrent deletion already removed. POSIX reports [NoSuchFileException] and nothing else; Windows
 * reports an [AccessDeniedException] for a path in its delete-pending state, and there any exception for a path
 * that no longer exists counts as gone. An unreadable path is not a removed one, so the existence fallback stays
 * on Windows: on POSIX it would hide a real access error.
 */
private fun gone(
    path: Path,
    exc: IOException,
): Boolean =
    exc is NoSuchFileException ||
        (ProcessPlatform.current().windows && (exc is AccessDeniedException || !Files.exists(path)))

/**
 * Stores [task]'s plan of [chunkCount] chunks together with the fake chunker's canonical clip, in one transaction:
 * what a finished CHUNKING phase leaves in the database (item 44).
 */
internal suspend fun storeChunkedPlan(
    fixture: EngineFixture,
    chunks: ChunkRepository,
    task: Long,
    chunkCount: Int,
) = chunks.storeChunked(
    task,
    plannedWindows(chunkCount),
    fixture.chunker.canonical.header,
    fixture.chunker.canonical.toCanonicalRows(),
)

// Deterministic race machinery (roadmap item 40): the atomic-transition specs interleave two commands by
// holding the writer lock, queuing the losing write first and the winner's write second, and releasing —
// the lock grants waiters in FIFO order, so the commit order is the launch order and the test is exact,
// not probabilistic.

/**
 * Names a coroutine that takes the writer lock: a race spec launches each racing write with its own
 * [LockWriter], so it can wait for exactly that write to queue. A context element, not the coroutine's
 * `Job`: `withContext` replaces the `Job` (the cancel's write runs under `NonCancellable`) but keeps the
 * element, and the engine worker's own writes never carry one.
 */
internal class LockWriter(
    private val name: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LockWriter>

    override fun toString() = name
}

/**
 * A [Mutex] that records which [LockWriter]s have called lock(), so a test can see a racing write reach it.
 * Each call takes a ticket in the same critical section as its record and is served in ticket order, so a
 * thread preempted right after its record cannot be overtaken by a later writer.
 */
internal class CountingMutex(
    private val delegate: Mutex = Mutex(),
) : Mutex by delegate {
    /** The writers that have called lock(); it only grows, so a wait for one writer cannot miss it. */
    val writers = MutableStateFlow<Set<LockWriter>>(emptySet())

    /**
     * Test-only: suspends a writer after it takes its ticket and before it waits for its turn, so a spec
     * can preempt it in that gap on purpose.
     */
    @Volatile
    var holdBeforeWait: CompletableDeferred<Unit>? = null

    private val state = Any()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var held = false

    override suspend fun lock(owner: Any?) {
        val writer = currentCoroutineContext()[LockWriter]
        val turn = CompletableDeferred<Unit>()
        synchronized(state) {
            writer?.let { calling -> writers.update { current -> current + calling } }
            if (held) {
                waiters.addLast(turn)
            } else {
                held = true
                turn.complete(Unit)
            }
        }
        try {
            holdBeforeWait?.await()
            turn.await()
            delegate.lock(owner)
        } catch (e: CancellationException) {
            // A cancelled waiter must not stall the tickets behind it: drop its ticket, or pass on a turn it
            // was already granted (delegate.lock is cancellation-safe, so it never holds the lock here).
            synchronized(state) {
                if (turn.isCompleted) {
                    releaseTurn()
                } else {
                    waiters.remove(turn)
                }
            }
            throw e
        }
    }

    override fun unlock(owner: Any?) {
        delegate.unlock(owner)
        synchronized(state) {
            releaseTurn()
        }
    }

    /** Releases the held turn and serves the next ticket; the caller holds [state]. */
    private fun releaseTurn() {
        held = false
        serveNext()
    }

    /** Grants the turn to the next ticket; the caller holds [state]. */
    private fun serveNext() {
        val next = waiters.removeFirstOrNull() ?: return
        held = true
        next.complete(Unit)
    }
}

/** A write transaction held open on the writer connection; once released it fails, so it rolls back. */
internal class HeldWrite(
    private val releaseLatch: CountDownLatch,
    val outcome: Deferred<Result<Unit>>,
) {
    fun release() = releaseLatch.countDown()
}

/**
 * Starts a write transaction that inserts [task] and stays open until released; returns once it is open.
 * The held transaction owns the writer lock, so every write launched after it waits behind it (reads do
 * not: they use their own read-only connections).
 */
internal suspend fun CoroutineScope.holdWriteTransaction(
    db: Database,
    task: Task,
): HeldWrite {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val outcome =
        async {
            runCatching {
                db.writeTransaction {
                    insertTaskRow(task)
                    held.countDown()
                    release.await(STALL_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    throw IOException("held transaction failed")
                }
            }
        }
    withContext(Dispatchers.IO) { held.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue() }
    return HeldWrite(release, outcome)
}

/**
 * Waits until [writer] has taken its ticket on the writer lock, so the write it launched is ahead of any
 * write launched after this wait. Fails naming [writer] after [timeout].
 */
internal suspend fun awaitLockCall(
    lock: CountingMutex,
    writer: LockWriter,
    timeout: Duration = PROBE_TIMEOUT_MS.milliseconds,
) {
    withContext(Dispatchers.Default) { withTimeoutOrNull(timeout) { lock.writers.first { writer in it } } }
        ?: throw AssertionError("writer $writer has not called lock() within $timeout")
}

/**
 * Launches [write] on Dispatchers.Default as its own [LockWriter] named [name] and returns once that write
 * has called the writer lock, so it commits before any write launched after it (see [awaitLockCall]).
 */
internal suspend fun <T> CoroutineScope.queueWrite(
    lock: CountingMutex,
    name: String,
    write: suspend CoroutineScope.() -> T,
): Deferred<T> {
    val writer = LockWriter(name)
    return async(Dispatchers.Default + writer, block = write).also { awaitLockCall(lock, writer) }
}

// The waits above bound the real-time probes: a probe waits for something that must happen, and the held
// transaction's safety bound outlasts every probe, so a stalled release cannot let a probe pass behind it.
private const val PROBE_TIMEOUT_MS = 10_000L
private const val STALL_SAFETY_TIMEOUT_MS = 30_000L

// A completion wait outlasts any load an engine test can meet and stays below runTest's own 60 s timeout,
// so a genuinely stuck task fails naming itself before the framework's anonymous timeout does.
private val COMPLETION_SAFETY_BOUND: Duration = 45_000.milliseconds
