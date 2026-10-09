package stt.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
    val diarizer: FakeDiarizer,
    val engine: ProcessingEngine,
    /** The ids of the tasks that reach COMPLETED, in order. */
    val completed: ReceiveChannel<Long>,
    /** The ids of the tasks that reach FAILED, in order. */
    val failed: ReceiveChannel<Long>,
    /** The task-progress events, in order. */
    val progress: ReceiveChannel<Event.TaskProgress>,
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
            diarizer = diarizer,
        )
    val completed = Channel<Long>(Channel.UNLIMITED)
    val failed = Channel<Long>(Channel.UNLIMITED)
    val progress = Channel<Event.TaskProgress>(Channel.UNLIMITED)
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
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
            }
        }
    }
    return EngineFixture(workDirectories, tasks, chunker, transcriber, processControl, diarizer, engine, completed, failed, progress)
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
