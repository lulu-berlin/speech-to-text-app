package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.TaskPhase
import stt.queue.TaskStatus

class ProcessingEngineTaskPauseSpec :
    BehaviorSpec({
        given("a task running when it is paused") {
            `when`("the task is paused and then resumed") {
                then("its process is frozen and continued, its status follows, and it still completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe task
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING

                        fixture.engine.pauseTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe true
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED
                        phaseOf(fixture, task) shouldBe TaskPhase.TRANSCRIBING

                        // The resume goes through QUEUED at once and keeps the phase the task was paused in.
                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe false
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING
                        phaseOf(fixture, task) shouldBe TaskPhase.TRANSCRIBING

                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe task
                        fixture.processControl.pauses shouldBe 1
                        fixture.processControl.resumes shouldBe 1
                    }
                }
            }
        }
    })
