package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.queue.TaskStatus

class ProcessingEngineSpec :
    BehaviorSpec({
        given("two queued tasks") {
            `when`("the engine runs") {
                then("they are processed one at a time in queue_position order") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe second

                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, second)
                        fixture.transcriber.audioFiles.map { taskIdOf(it.parent) } shouldBe listOf(first, second)
                        fixture.chunker.probe.max shouldBe 1
                        fixture.transcriber.probe.max shouldBe 1
                        tasks.find(first)?.task?.status shouldBe TaskStatus.COMPLETED
                        tasks.find(second)?.task?.status shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }

        given("three queued tasks of which the first is running") {
            `when`("the queue is reordered while that task runs") {
                then("the running task is not interrupted and the new order decides what starts next") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)
                        val third = enqueueTask(tasks, fixture.workDirectories, "third.wav", queuePosition = 2, createdAt = 3)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        // The worker is inside the first task's transcription; reorder behind its back.
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        tasks.setQueuePositions(listOf(third, second, first))
                        fixture.engine.wake()
                        fixture.transcriber.hold = null
                        release.complete(Unit)

                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe third
                        fixture.completed.receive() shouldBe second
                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, third, second)
                    }
                }
            }
        }
    })
