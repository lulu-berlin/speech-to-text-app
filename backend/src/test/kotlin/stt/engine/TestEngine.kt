package stt.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import stt.config.testConfig
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.TestDataDir
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One engine and everything a spec drives around it (fake chunker and transcriber, repositories, events). */
internal class EngineFixture(
    val workDirectories: TaskWorkDirectories,
    val tasks: TaskRepository,
    val chunker: FakeChunker,
    val transcriber: FakeTranscriber,
    val processControl: FakeProcessControl,
    val engine: ProcessingEngine,
    /** The ids of the tasks that reach COMPLETED, in order. */
    val completed: ReceiveChannel<Long>,
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
    val engine =
        ProcessingEngine(
            scope = backgroundScope,
            config = testConfig(dataDir.dataDir),
            tasks = tasks,
            chunks = ChunkRepository(db),
            segments = SegmentRepository(db),
            queueState = QueueStateRepository(db),
            workDirectories = workDirectories,
            chunker = chunker,
            transcriber = transcriber,
            processControl = processControl,
        )
    val completed = Channel<Long>(Channel.UNLIMITED)
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events
            .filterIsInstance<Event.TaskUpdated>()
            .filter { it.task.task.status == TaskStatus.COMPLETED }
            .collect { completed.send(it.task.id) }
    }
    return EngineFixture(workDirectories, tasks, chunker, transcriber, processControl, engine, completed)
}

/** Inserts a QUEUED task at [queuePosition] with an original in its work directory, as an upload does (ADR 0024). */
internal suspend fun enqueueTask(
    tasks: TaskRepository,
    workDirectories: TaskWorkDirectories,
    filename: String,
    queuePosition: Int,
    createdAt: Long,
): Long {
    val id =
        tasks.insert(
            queuedTask(filename, createdAt).copy(queuePosition = queuePosition, speakersMode = SpeakersMode.OFF),
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

/**
 * The tasks the engine starts while [window] of real time passes, returning early as soon as a second one
 * starts. "Nothing happened" cannot be awaited, so the test pumps the worker itself: [runCurrent] runs it
 * on the test dispatcher, and each short real delay lets the file and database I/O in between finish.
 */
internal suspend fun TestScope.tasksStartedWithin(
    fixture: EngineFixture,
    window: Duration,
): List<Long> {
    val deadline = System.nanoTime() + window.inWholeNanoseconds
    runCurrent()
    while (System.nanoTime() < deadline && fixture.chunker.workDirs.size <= 1) {
        withContext(Dispatchers.Default) { delay(2.milliseconds) }
        runCurrent()
    }
    return fixture.chunker.workDirs.map(::taskIdOf)
}
