package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.runTest
import stt.db.Database
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.db.TestDataDir
import stt.db.queuedTask
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

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
                            held.outcome
                                .await()
                                .isFailure
                                .shouldBeTrue()
                        }
                    }
                }
            }
        }

        given("a paused task whose cancel races its resume") {
            `when`("the resume's read passes first and its write lands after the cancel") {
                then("the task ends CANCELED, the resume is refused, and the queue stays live") {
                    runTest {
                        val dataDir = tempdir("stt-engine").toPath()
                        val lock = CountingMutex()
                        Database.open(dataDir, lock).use { db ->
                            val fixture = engineUnderTest(TestDataDir(dataDir), db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                            fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L).shouldBeTrue()
                            fixture.tasks.setStatus(task, TaskStatus.PAUSED)
                            fixture.tasks.setPhase(task, TaskPhase.TRANSCRIBING)

                            // Hold the writer lock; the cancel's conditional write queues first, then the
                            // resume's. The resume's read gate already passed on PAUSED, and its write waits
                            // behind the cancel — reads use their own connections.
                            val held = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 2L))
                            val base = lock.lockCalls.value
                            val cancel = async(Dispatchers.Default) { fixture.engine.cancelTask(task) }
                            awaitLockCall(lock, base + 1)
                            val resume = async(Dispatchers.Default) { fixture.engine.resumeTask(task) }
                            awaitLockCall(lock, base + 2)

                            // Release: the cancel commits first, so the resume's write lands on a task that is
                            // no longer PAUSED. It must be refused — a resume that lost must not unfreeze the
                            // process or the pause gate, which would release a different paused task.
                            held.release()
                            cancel.await() shouldBe TaskCommandOutcome.Accepted
                            resume.await() shouldBe TaskCommandOutcome.Illegal(TaskStatus.CANCELED, TaskStatus.QUEUED)
                            statusOf(fixture, task) shouldBe TaskStatus.CANCELED
                            fixture.processControl.resumes shouldBe 0
                            fixture.processControl.frozen shouldBe false
                            fixture.tasks.startedPaused() shouldBe null

                            // The queue stays live: the canceled task left it, and a next task still completes.
                            val second =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "next.wav", queuePosition = 1, createdAt = 3)
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe second
                            held.outcome
                                .await()
                                .isFailure
                                .shouldBeTrue()
                        }
                    }
                }
            }
        }

        given("a running task whose completion races its deletion") {
            `when`("the completion commits while the delete is on its cancel-first path") {
                then("the row still goes and the file is freed") {
                    runTest {
                        val dataDir = tempdir("stt-engine").toPath()
                        val lock = CountingMutex()
                        Database.open(dataDir, lock).use { db ->
                            val fixture = engineUnderTest(TestDataDir(dataDir), db)
                            // The "file": the task's work directory and the original inside it.
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                            val workDir = fixture.workDirectories.workDir(task)
                            Files.exists(workDir).shouldBeTrue()
                            fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L).shouldBeTrue()

                            // Hold the writer lock; the completion (the worker's terminal write) queues
                            // first, then the delete's writes — its read saw RUNNING, so it cancels first
                            // (the item 12 contract), and deletes the row only after the cancel is done:
                            // the row's delete is sequenced behind the cancel's write, so queueing the
                            // completion and the cancel's write is enough to fix the whole order.
                            val held = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 2L))
                            val base = lock.lockCalls.value
                            val completion = async(Dispatchers.Default) { fixture.tasks.setCompleted(task, 9_000L) }
                            awaitLockCall(lock, base + 1)
                            val removal = async(Dispatchers.Default) { fixture.engine.deleteTask(task) }
                            awaitLockCall(lock, base + 2) // the cancel's conditional write

                            // Release: the completion commits COMPLETED first, so the cancel loses — a
                            // finished task cannot be cancelled — and the row still goes: delete wins.
                            held.release()
                            completion.await()
                            removal.await() shouldBe TaskCommandOutcome.Accepted
                            fixture.tasks.find(task) shouldBe null
                            Files.exists(workDir).shouldBeFalse()

                            // The deletion is the task's last word: it is published once, and nothing
                            // referring to the id — not even the completion — re-appears after it.
                            fixture.events.receive() shouldBe Event.TaskDeleted(task)
                            drain(fixture.events)
                                .filterIsInstance<Event.TaskUpdated>()
                                .none { it.task.id == task }
                                .shouldBeTrue()
                            held.outcome
                                .await()
                                .isFailure
                                .shouldBeTrue()
                        }
                    }
                }
            }
        }

        given("a running task whose cancel races its pause") {
            `when`("the pause's read passes first and its write lands after the cancel") {
                then("the task ends CANCELED, the pause is refused, and nothing is left dead PAUSED") {
                    runTest {
                        val dataDir = tempdir("stt-engine").toPath()
                        val lock = CountingMutex()
                        Database.open(dataDir, lock).use { db ->
                            val fixture = engineUnderTest(TestDataDir(dataDir), db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                            fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L).shouldBeTrue()

                            // Hold the writer lock; the cancel's conditional write queues first, then the
                            // pause's. The pause's read gate already passed on RUNNING, and its write waits
                            // behind the cancel — reads use their own connections.
                            val held = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 2L))
                            val base = lock.lockCalls.value
                            val cancel = async(Dispatchers.Default) { fixture.engine.cancelTask(task) }
                            awaitLockCall(lock, base + 1)
                            val pause = async(Dispatchers.Default) { fixture.engine.pauseTask(task) }
                            awaitLockCall(lock, base + 2)

                            // Release: the cancel commits CANCELED first, so the pause's write lands on a
                            // task that is no longer pausable. It must be refused — and freeze nothing: a
                            // started PAUSED row with no way forward is the queue's blocking condition.
                            held.release()
                            cancel.await() shouldBe TaskCommandOutcome.Accepted
                            pause.await() shouldBe TaskCommandOutcome.Illegal(TaskStatus.CANCELED, TaskStatus.PAUSED)
                            statusOf(fixture, task) shouldBe TaskStatus.CANCELED
                            fixture.processControl.pauses shouldBe 0
                            fixture.processControl.frozen shouldBe false
                            fixture.tasks.startedPaused() shouldBe null

                            // The queue stays live: the canceled task left it, and a next task still completes.
                            val second =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "next.wav", queuePosition = 1, createdAt = 3)
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe second
                            held.outcome
                                .await()
                                .isFailure
                                .shouldBeTrue()
                        }
                    }
                }
            }
        }

        given("a pause that is accepted while its task's last process is already exiting") {
            `when`("the task still finishes") {
                then("the next task, including its DIARIZING phase, runs to completion: the stale pause stalls nothing") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        // The next task has speakers, so its DIARIZING phase passes the pause gate.
                        val next =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "next.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(next to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        fixture.engine.pauseTask(first) shouldBe TaskCommandOutcome.Accepted

                        // The fake process ignores the freeze, as when the pause lands after the task's last
                        // process has exited: the task still finishes, and completion wins over the pause.
                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        pumpUntil(3.seconds) { statusOf(fixture, next) == TaskStatus.COMPLETED }
                        statusOf(fixture, first) shouldBe TaskStatus.COMPLETED
                        fixture.diarizer.passedGate shouldBe true
                        statusOf(fixture, next) shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }
    })
