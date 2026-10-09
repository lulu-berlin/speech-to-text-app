package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.TaskPhase
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.milliseconds

class ProcessingEngineCancelSpec :
    BehaviorSpec({
        given("two queued tasks of which the first is running") {
            `when`("the running task is cancelled") {
                then("its process is killed, it is CANCELED, and the worker starts the next task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        // The worker is parked inside the first task's transcription: only a cancel can end it.
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first

                        fixture.engine.cancelTask(first) shouldBe TaskCommandOutcome.Accepted

                        // The first task's transcription was cancelled, so its process died with it.
                        fixture.transcriber.cancelled.map { taskIdOf(it.parent) } shouldBe listOf(first)
                        statusOf(fixture, first) shouldBe TaskStatus.CANCELED
                        fixture.tasks
                            .find(first)
                            ?.task
                            ?.finishedAt
                            .shouldNotBeNull()
                        phaseOf(fixture, first) shouldBe TaskPhase.TRANSCRIBING

                        // ... and the worker moves on to the next queued task.
                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe second
                    }
                }
            }
        }

        given("a queued task behind a running one") {
            `when`("the queued task is cancelled") {
                then("it is CANCELED and never started") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val running = enqueueTask(tasks, fixture.workDirectories, "running.wav", queuePosition = 0, createdAt = 1)
                        val queued = enqueueTask(tasks, fixture.workDirectories, "queued.wav", queuePosition = 1, createdAt = 2)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe running

                        fixture.engine.cancelTask(queued) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, queued) shouldBe TaskStatus.CANCELED
                        fixture.tasks
                            .find(queued)
                            ?.task
                            ?.finishedAt
                            .shouldNotBeNull()

                        // The running task finishes; the cancelled one behind it is never picked up.
                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe running
                        tasksStartedWithin(fixture, 200.milliseconds) shouldBe listOf(running)
                        fixture.transcriber.audioFiles.map { taskIdOf(it.parent) } shouldBe listOf(running)
                        fixture.transcriber.cancelled.shouldBeEmpty()
                        statusOf(fixture, queued) shouldBe TaskStatus.CANCELED
                    }
                }
            }
        }
    })
