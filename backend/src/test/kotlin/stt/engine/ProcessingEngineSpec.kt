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
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.queue.TaskStatus

class ProcessingEngineSpec :
    BehaviorSpec({
        given("two queued tasks") {
            `when`("the engine runs") {
                then("they are processed one at a time in queue_position order") {
                    withEngineDatabase { dataDir, db ->
                        val workDirectories = TaskWorkDirectories(dataDir.dataDir)
                        val tasks = TaskRepository(db, workDirectories)
                        val first = enqueueTask(tasks, workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

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
