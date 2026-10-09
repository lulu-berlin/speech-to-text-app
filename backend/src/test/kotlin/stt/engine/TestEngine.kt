package stt.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import stt.config.testConfig
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.TestDataDir
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Files
import java.nio.file.Path

/** One engine and everything a spec drives around it (fake chunker and transcriber, repositories, events). */
internal class EngineFixture(
    val workDirectories: TaskWorkDirectories,
    val tasks: TaskRepository,
    val chunker: FakeChunker,
    val transcriber: FakeTranscriber,
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
    val engine =
        ProcessingEngine(
            scope = backgroundScope,
            config = testConfig(dataDir.dataDir),
            tasks = tasks,
            chunks = ChunkRepository(db),
            segments = SegmentRepository(db),
            workDirectories = workDirectories,
            chunker = chunker,
            transcriber = transcriber,
        )
    val completed = Channel<Long>(Channel.UNLIMITED)
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events
            .filterIsInstance<Event.TaskUpdated>()
            .filter { it.task.task.status == TaskStatus.COMPLETED }
            .collect { completed.send(it.task.id) }
    }
    return EngineFixture(workDirectories, tasks, chunker, transcriber, engine, completed)
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
