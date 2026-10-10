package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.TaskPhase
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

class ProcessingEnginePhaseSpec :
    BehaviorSpec({
        given("tasks at each phase-clearing boundary") {
            `when`("the engine starts and they move on") {
                then("the phase is kept on FAILED and CANCELED, and cleared on recovery, retry and completion") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val recovered =
                            enqueueTask(fixture.tasks, fixture.workDirectories, "recovered.wav", queuePosition = 1, createdAt = 1)
                        val completes =
                            enqueueTask(fixture.tasks, fixture.workDirectories, "completes.wav", queuePosition = 0, createdAt = 2)
                        val failed = enqueueTask(fixture.tasks, fixture.workDirectories, "failed.wav", queuePosition = 2, createdAt = 3)
                        val canceled = enqueueTask(fixture.tasks, fixture.workDirectories, "canceled.wav", queuePosition = 3, createdAt = 4)
                        val retried = enqueueTask(fixture.tasks, fixture.workDirectories, "retried.wav", queuePosition = 4, createdAt = 5)
                        // Terminal states with the phase they stopped in; recovered is a crash left RUNNING.
                        fixture.tasks.setPhase(failed, TaskPhase.TRANSCRIBING)
                        fixture.tasks.setStatus(failed, TaskStatus.FAILED)
                        fixture.tasks.setPhase(canceled, TaskPhase.TRANSCRIBING)
                        fixture.tasks.setStatus(canceled, TaskStatus.CANCELED)
                        fixture.tasks.setPhase(retried, TaskPhase.TRANSCRIBING)
                        fixture.tasks.setStatus(retried, TaskStatus.FAILED)
                        fixture.tasks.setRunningIfQueued(recovered, startedAt = 1_000L)
                        fixture.tasks.setPhase(recovered, TaskPhase.TRANSCRIBING)

                        // Paused, so the retried task stays queued and its cleared state is observable.
                        fixture.engine.pauseQueue()
                        fixture.engine.start()
                        pumpUntil(2.seconds) { statusOf(fixture, recovered) == TaskStatus.QUEUED }

                        // Restart recovery is the only path that changes a terminal-less running task.
                        statusOf(fixture, recovered) shouldBe TaskStatus.QUEUED
                        phaseOf(fixture, recovered) shouldBe null

                        // FAILED and CANCELED keep the phase they stopped in.
                        phaseOf(fixture, failed) shouldBe TaskPhase.TRANSCRIBING
                        phaseOf(fixture, canceled) shouldBe TaskPhase.TRANSCRIBING

                        // A retry clears it the moment the task is queued again.
                        fixture.engine.retryTask(retried) shouldBe TaskCommandOutcome.Accepted
                        phaseOf(fixture, retried) shouldBe null
                        statusOf(fixture, retried) shouldBe TaskStatus.QUEUED

                        // Completion clears it too.
                        fixture.engine.resumeQueue()
                        pumpUntil(2.seconds) { statusOf(fixture, completes) == TaskStatus.COMPLETED }
                        phaseOf(fixture, completes) shouldBe null
                    }
                }
            }
        }
    })
