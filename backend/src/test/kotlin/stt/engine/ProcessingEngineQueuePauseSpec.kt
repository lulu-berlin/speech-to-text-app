package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.milliseconds

class ProcessingEngineQueuePauseSpec :
    BehaviorSpec({
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
