package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import stt.config.testConfig
import stt.db.ChunkRepository
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.hours

class ProcessingEngineIdleSpec :
    BehaviorSpec({
        given("an engine with an empty queue") {
            `when`("it has been idle for an hour and a task is enqueued then") {
                then("it starts that task as soon as it is signalled, without a timer having fired") {
                    withEngineDatabase { dataDir, db ->
                        val workDirectories = TaskWorkDirectories(dataDir.dataDir)
                        val tasks = TaskRepository(db, workDirectories)
                        val chunker = FakeChunker()
                        val engine =
                            ProcessingEngine(
                                scope = backgroundScope,
                                config = testConfig(dataDir.dataDir),
                                tasks = tasks,
                                chunks = ChunkRepository(db),
                                segments = SegmentRepository(db),
                                workDirectories = workDirectories,
                                chunker = chunker,
                                transcriber = FakeTranscriber(),
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
                        runCurrent()

                        // Nothing is queued, so nothing may happen however much idle time passes:
                        // a polling worker would instead have wake-ups scheduled all over this hour.
                        chunker.workDirs.shouldBeEmpty()
                        advanceTimeBy(1.hours)
                        runCurrent()
                        chunker.workDirs.shouldBeEmpty()
                        val idleTime = testScheduler.currentTime

                        val id = enqueueTask(tasks, workDirectories, "idle.wav", queuePosition = 0, createdAt = 1)
                        engine.wake()

                        completed.receive() shouldBe id
                        testScheduler.currentTime shouldBe idleTime
                    }
                }
            }
        }
    })
