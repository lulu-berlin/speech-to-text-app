package stt.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import kotlinx.coroutines.withTimeout
import io.kotest.matchers.booleans.shouldBeTrue
import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import stt.config.testConfig
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.db.Task
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.TestDataDir
import stt.db.insertTaskRow
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One engine and everything a spec drives around it (fake chunker and transcriber, repositories, events). */
internal class EngineFixture(
    val workDirectories: TaskWorkDirectories,
    val tasks: TaskRepository,
    val chunker: FakeChunker,
    val transcriber: FakeTranscriber,
    val processControl: FakeProcessControl,
    val diarizer: FakeDiarizer,
    val pcmDecoder: FakePcmDecoder,
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
 */
internal fun TestScope.engineUnderTest(
    dataDir: TestDataDir,
    db: Database,
): EngineFixture {
    val workDirectories = TaskWorkDirectories(dataDir.dataDir)
    val tasks = TaskRepository(db, workDirectories)
    val chunker = FakeChunker()
    val transcriber = FakeTranscriber()
    val processControl = FakeProcessControl()
    val diarizer = FakeDiarizer()
    val pcmDecoder = FakePcmDecoder()
    val engine =
        ProcessingEngine(
            scope = backgroundScope,
            config = testConfig(dataDir.dataDir),
            tasks = tasks,
            chunks = ChunkRepository(db),
            segments = SegmentRepository(db),
            queueState = QueueStateRepository(db),
            settings = SettingsRepository(db),
            workDirectories = workDirectories,
            chunker = chunker,
            transcriber = transcriber,
            processControl = processControl,
            diarizer = diarizer,
            pcmDecoder = pcmDecoder,
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
        workDirectories,
        tasks,
        chunker,
        transcriber,
        processControl,
        diarizer,
        pcmDecoder,
        engine,
        completed,
        failed,
        progress,
        events,
    )
}

/** Inserts a QUEUED task at [queuePosition] with an original in its work directory, as an upload does (ADR 0024). */
internal suspend fun enqueueTask(
    tasks: TaskRepository,
    workDirectories: TaskWorkDirectories,
    filename: String,
    queuePosition: Int,
    createdAt: Long,
    speakersMode: SpeakersMode = SpeakersMode.OFF,
): Long {
    val id =
        tasks.insert(
            queuedTask(filename, createdAt).copy(queuePosition = queuePosition, speakersMode = speakersMode),
        )
    val source = workDirectories.source(id)
    Files.createDirectories(source.parent)
    Files.writeString(source, "fake source audio")
    return id
}

/** The id of the task a work path belongs to: `<dataDir>/work/<id>[/…]` (ADR 0024). */
internal fun taskIdOf(workPath: Path): Long = workPath.fileName.toString().toLong()

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

/** Writes [chunkCount] FLAC files for a task's plan, as a previous run would have left them. */
internal suspend fun exportedPlan(
    fixture: EngineFixture,
    task: Long,
    chunkCount: Int,
): List<ExportedChunk> {
    val workDir = fixture.workDirectories.workDir(task)
    return (0 until chunkCount).map { idx ->
        val file = workDir.resolve("chunk-$idx.flac")
        Files.writeString(file, "fake flac")
        ExportedChunk(PlannedChunk(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L), file)
    }
}

// Deterministic race machinery (roadmap item 40): the atomic-transition specs interleave two commands by
// holding the writer lock, queuing the losing write first and the winner's write second, and releasing —
// the lock grants waiters in FIFO order, so the commit order is the launch order and the test is exact,
// not probabilistic.

/** A [Mutex] that counts its lock() calls, so a test can see a caller start waiting for it. */
internal class CountingMutex(
    private val delegate: Mutex = Mutex(),
) : Mutex by delegate {
    val lockCalls = MutableStateFlow(0)

    override suspend fun lock(owner: Any?) {
        lockCalls.update { it + 1 }
        delegate.lock(owner)
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
 * Waits until the writer lock has been called [call] times: the operation launched before this wait is
 * now waiting on the lock, so it cannot commit before another operation launched after this wait.
 */
internal suspend fun awaitLockCall(lock: CountingMutex, call: Int) {
    withContext(Dispatchers.Default) { withTimeout(PROBE_TIMEOUT_MS) { lock.lockCalls.first { it == call } } }
}

// The waits above bound the real-time probes: a probe waits for something that must happen, and the held
// transaction's safety bound outlasts every probe, so a stalled release cannot let a probe pass behind it.
private const val PROBE_TIMEOUT_MS = 10_000L
private const val STALL_SAFETY_TIMEOUT_MS = 30_000L
