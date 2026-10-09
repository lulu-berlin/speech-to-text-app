package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import stt.config.testConfig
import stt.db.ChunkRepository
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Files
import java.nio.file.Path

class ProcessingEngineSpec :
    BehaviorSpec({
        given("two queued tasks") {
            `when`("the engine runs") {
                then("they are processed one at a time in queue_position order") {
                    withEngineDatabase { dataDir, db ->
                        val workDirectories = TaskWorkDirectories(dataDir.dataDir)
                        val tasks = TaskRepository(db, workDirectories)
                        val first = enqueue(tasks, workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueue(tasks, workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

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
                        backgroundScope.launch {
                            engine.events
                                .filterIsInstance<Event.TaskUpdated>()
                                .filter { it.task.task.status == TaskStatus.COMPLETED }
                                .collect { completed.send(it.task.id) }
                        }
                        runCurrent()
                        engine.start()

                        completed.receive() shouldBe first
                        completed.receive() shouldBe second

                        chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, second)
                        transcriber.audioFiles.map { taskIdOf(it.parent) } shouldBe listOf(first, second)
                        chunker.probe.max shouldBe 1
                        transcriber.probe.max shouldBe 1
                        tasks.find(first)?.task?.status shouldBe TaskStatus.COMPLETED
                        tasks.find(second)?.task?.status shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }
    })

/** The id of the task a work path belongs to: `<dataDir>/work/<id>[/…]` (ADR 0024). */
private fun taskIdOf(workPath: Path): Long = workPath.fileName.toString().toLong()

/** Inserts a QUEUED task at [queuePosition] with an original in its work directory, as an upload does (ADR 0024). */
private suspend fun enqueue(
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
