package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.TaskPhase
import stt.queue.TaskStatus

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
    })
