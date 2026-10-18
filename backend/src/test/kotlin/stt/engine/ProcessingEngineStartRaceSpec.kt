package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.queue.TaskStatus

class ProcessingEngineStartRaceSpec :
    BehaviorSpec({
        given("a cancel that arrives while the worker is starting the task") {
            `when`("the task's job has not started its run yet") {
                then("the run never starts a process and the task stays CANCELED, never COMPLETED") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)

                        commandInStartWindow(fixture) { fixture.engine.cancelTask(task) } shouldBe TaskCommandOutcome.Accepted

                        // A later task runs only after this one is over: in the order the worker gives COMPLETED,
                        // the second one comes first only if the cancelled run never completed.
                        val next = enqueueTask(fixture.tasks, "next.wav", queuePosition = 1, createdAt = 2)
                        fixture.engine.taskCreated(next)

                        fixture.completed.receive() shouldBe next
                        statusOf(fixture, task) shouldBe TaskStatus.CANCELED
                        fixture.transcriber.audioFiles.map { taskIdOf(it.parent) } shouldBe listOf(next)
                    }
                }
            }
        }
        given("a pause that arrives while the worker is starting the task") {
            `when`("the task's job has not started its run yet") {
                then("the run never starts a process, the task stays PAUSED, and resuming runs it to COMPLETED") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        val resumeIssued = CompletableDeferred<Unit>()

                        // The window stays open while the resume lands: the aborted run is still registered.
                        commandInStartWindow(fixture, holdOpen = { resumeIssued.await() }) {
                            fixture.engine.pauseTask(task)
                        } shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED
                        fixture.transcriber.audioFiles.shouldBeEmpty()

                        // The run never started, so the resume queues the task again instead of writing RUNNING over a
                        // run that is about to end (which would leave it owned by nobody). Only then is the window freed.
                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, task) shouldBe TaskStatus.QUEUED
                        resumeIssued.complete(Unit)

                        fixture.completed.receive() shouldBe task
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-0.flac")
                        drain(fixture.failed).isEmpty().shouldBeTrue()
                    }
                }
            }
        }
        given("a delete that arrives while the worker is starting the task") {
            `when`("the task's job has not started its run yet") {
                then("the run starts no process, the row is gone, and the next queued task still runs") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)

                        commandInStartWindow(fixture) { fixture.engine.deleteTask(task) } shouldBe TaskCommandOutcome.Accepted
                        fixture.tasks.find(task) shouldBe null
                        fixture.transcriber.audioFiles.shouldBeEmpty()
                        drain(fixture.completed).isEmpty().shouldBeTrue()

                        // The worker was not left inside the deleted run.
                        val next = enqueueTask(fixture.tasks, "next.wav", queuePosition = 1, createdAt = 2)
                        fixture.engine.taskCreated(next)
                        fixture.completed.receive() shouldBe next
                    }
                }
            }
        }
    })

/**
 * Runs [command] from inside the worker's start window — `beforeTaskStarts`, after the task's job is registered and
 * before it runs — once: the hook clears itself, so the next start is untouched. Returns the command's outcome, and
 * keeps the window open for [holdOpen] if given.
 */
private suspend fun commandInStartWindow(
    fixture: EngineFixture,
    holdOpen: suspend () -> Unit = {},
    command: suspend () -> TaskCommandOutcome,
): TaskCommandOutcome {
    val outcome = CompletableDeferred<TaskCommandOutcome>()
    fixture.engine.beforeTaskStarts = {
        fixture.engine.beforeTaskStarts = null
        outcome.complete(command())
        holdOpen()
    }
    fixture.engine.start()
    return outcome.await()
}
