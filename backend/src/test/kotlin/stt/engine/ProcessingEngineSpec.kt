package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import stt.queue.TaskStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

        given("a queue that is paused while a task runs") {
            `when`("that task finishes") {
                then("nothing new starts until the queue is resumed, and then the next queued task runs") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        fixture.engine.pauseQueue()
                        fixture.transcriber.hold = null
                        release.complete(Unit)

                        // A queue pause does not interrupt the task that is already running ...
                        fixture.completed.receive() shouldBe first
                        // ... and it must not start another one, however long it is left alone.
                        tasksStartedWithin(fixture, 200.milliseconds) shouldBe listOf(first)

                        fixture.engine.resumeQueue()
                        fixture.completed.receive() shouldBe second
                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, second)
                    }
                }
            }
        }

        given("a task running when the queue is paused") {
            `when`("the queue is paused and then resumed") {
                then("its process is frozen and continued, and the task still completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        fixture.processControl.frozen shouldBe false
                        fixture.engine.pauseQueue()
                        fixture.processControl.frozen shouldBe true
                        fixture.engine.resumeQueue()
                        fixture.processControl.frozen shouldBe false

                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe first
                        fixture.processControl.pauses shouldBe 1
                        fixture.processControl.resumes shouldBe 1
                    }
                }
            }
        }
    })

/**
 * The tasks the engine starts while [window] of real time passes, returning early as soon as a second one
 * starts. "Nothing happened" cannot be awaited, so the test pumps the worker itself: [runCurrent] runs it
 * on the test dispatcher, and each short real delay lets the file and database I/O in between finish.
 */
private suspend fun TestScope.tasksStartedWithin(
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
