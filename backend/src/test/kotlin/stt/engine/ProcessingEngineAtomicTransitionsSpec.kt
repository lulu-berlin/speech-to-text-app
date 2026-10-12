package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import stt.db.Database
import stt.db.TestDataDir
import stt.db.queuedTask
import stt.queue.TaskStatus

/**
 * The engine's task commands under races (roadmap item 40): a command never overwrites a newer state.
 * Each race is driven deterministically on the writer lock — the losing write queues first, the winning
 * write queues second, and releasing the hold commits them in that order, so the interleaving is exact.
 */
class ProcessingEngineAtomicTransitionsSpec :
    BehaviorSpec({
        given("a running task whose completion races its pause") {
            `when`("the pause's read passes first and its write lands after the completion") {
                then("the task stays COMPLETED, the pause is refused, and the queue keeps working") {
                    runTest {
                        val dataDir = tempdir("stt-engine").toPath()
                        val lock = CountingMutex()
                        Database.open(dataDir, lock).use { db ->
                            val fixture = engineUnderTest(TestDataDir(dataDir), db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                            fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L).shouldBeTrue()

                            // Hold the writer lock; the completion (the worker's terminal write) queues first,
                            // then the pause's write. The pause's read gate already passed on RUNNING, and its
                            // write waits behind the completion — reads use their own connections. The count of
                            // lock calls is read after every setup write, so the racers are observed relative to
                            // it; each runs on Dispatchers.Default, independent of the test scheduler.
                            val held = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 2L))
                            val base = lock.lockCalls.value
                            val completion = async(Dispatchers.Default) { fixture.tasks.setCompleted(task, 9_000L) }
                            awaitLockCall(lock, base + 1)
                            val pause = async(Dispatchers.Default) { fixture.engine.pauseTask(task) }
                            awaitLockCall(lock, base + 2)

                            // Release: the completion commits first, so the pause's write lands on a task that
                            // is no longer pausable. It must be refused, never written over.
                            held.release()
                            completion.await()
                            pause.await() shouldBe TaskCommandOutcome.Illegal(TaskStatus.COMPLETED, TaskStatus.PAUSED)
                            statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                            // The losing pause must not freeze anything, and must not leave a dead started
                            // PAUSED row: that is the queue-blocking condition.
                            fixture.processControl.pauses shouldBe 0
                            fixture.tasks.startedPaused() shouldBe null

                            // The queue keeps working: the next task still runs to completion.
                            val second =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "next.wav", queuePosition = 1, createdAt = 3)
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe second
                            held.outcome.await().isFailure.shouldBeTrue()
                        }
                    }
                }
            }
        }
    })