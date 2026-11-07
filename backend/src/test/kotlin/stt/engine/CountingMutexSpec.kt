@file:OptIn(ExperimentalCoroutinesApi::class)

package stt.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import stt.db.CountingMutex
import stt.db.LockWriter
import stt.db.awaitLockCall
import stt.db.queueWrite
import kotlin.time.Duration.Companion.seconds

/**
 * The writer-lock probe of the race specs (roadmap item 55): a wait for a queued write is tied to its own
 * writer, so neither a conflated call count nor another writer's lock call can decide it.
 */
class CountingMutexSpec :
    BehaviorSpec({
        given("a held writer lock with two writers queued behind it") {
            `when`("both have called lock() before either wait starts, so an exact call count is already past the first writer's") {
                then("each wait completes for its own writer, and a write that finishes without calling lock() fails naming it") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock()
                        val first = LockWriter("first")
                        val second = LockWriter("second")
                        val firstJob = launch(first) { lock.withLock {} }
                        val secondJob = launch(second) { lock.withLock {} }
                        // Both queued writers are inside lock() before any wait starts: a call count would already
                        // have jumped past the first writer's number, which an exact-count wait would miss.
                        runCurrent()
                        lock.writers.value shouldBe setOf(first, second)

                        awaitLockCall(lock, first, firstJob)
                        awaitLockCall(lock, second, secondJob)
                        // A write that returns without ever taking the lock is the failure the wait names, at
                        // once: the writer's own completion decides it, not a clock.
                        shouldThrow<AssertionError> { queueWrite(lock, "not-yet") { } }
                            .message shouldBe "writer not-yet finished without calling lock()"

                        lock.unlock()
                    }
                }
            }
        }
        given("a writer held between its record and its wait") {
            `when`("a later writer reaches the lock meanwhile") {
                then("the held writer is still served first") {
                    runTest {
                        val lock = CountingMutex()
                        val order = mutableListOf<String>()
                        lock.lock() // the test holds the lock, so both writers queue behind it
                        val first = LockWriter("first")
                        val second = LockWriter("second")
                        val hold = CompletableDeferred<Unit>()
                        lock.holdBeforeWait = hold
                        launch(first) { lock.withLock { order += "first" } }
                        runCurrent()
                        // The hold catches the first writer after its record, before its wait.
                        lock.writers.value shouldBe setOf(first)
                        lock.holdBeforeWait = null
                        val secondJob = launch(second) { lock.withLock { order += "second" } }
                        awaitLockCall(lock, second, secondJob)

                        hold.complete(Unit)
                        lock.unlock()
                        runCurrent()
                        order shouldBe listOf("first", "second")
                    }
                }
            }
        }
        given("a held writer lock and a writer that has not reached it") {
            `when`("the writer takes its ticket later than the old 10 s probe bound") {
                then("the wait succeeds once the writer reaches the lock, without a clock of its own") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock() // the test holds the lock, so the late writer queues behind it
                        val late = LockWriter("late")
                        val mayLock = CompletableDeferred<Unit>()
                        val lateJob =
                            launch(Dispatchers.Default + late) {
                                mayLock.await()
                                lock.withLock {}
                            }
                        // Hold the writer off the lock past the probe's old 10 s bound: a wait that
                        // expires on a clock of its own fails here instead of awaiting its writer.
                        backgroundScope.releaseAfter(11.seconds, mayLock)

                        awaitLockCall(lock, late, lateJob)

                        lock.unlock()
                    }
                }
            }
        }
        given("a held writer lock and a write cancelled before it takes the lock") {
            `when`("the write is cancelled") {
                then("the wait fails naming the writer instead of hanging") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock() // the test holds the lock, so the write can never take it
                        val writer = LockWriter("cancelled")
                        val write = async(Dispatchers.Default + writer) { awaitCancellation() }
                        write.cancel()

                        shouldThrow<AssertionError> { awaitLockCall(lock, writer, write) }
                            .message shouldBe "writer cancelled finished without calling lock()"

                        lock.unlock()
                    }
                }
            }
        }
        given("a held writer lock and a write that fails before it takes the lock") {
            `when`("the write throws") {
                then("the wait fails naming the writer and keeps the cause") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock() // the test holds the lock, so the write can never take it

                        val failure = shouldThrow<AssertionError> { queueWrite(lock, "failing") { error("boom") } }

                        failure.message shouldBe "writer failing finished without calling lock()"
                        // Stack-trace recovery can interpose a copy of the assertion, so the write's own
                        // failure is found anywhere in the chain rather than at a fixed depth.
                        generateSequence<Throwable>(failure) { it.cause }.any { it.message == "boom" } shouldBe true
                        lock.unlock()
                    }
                }
            }
        }
        given("a writer launched with no probe argument") {
            `when`("its job ends without taking the lock") {
                then("the wait fails naming the writer instead of relying on the test timeout") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock() // the test holds the lock, so the writer never takes it
                        val writer = LockWriter("ended")
                        val job = launch(writer) { } // returns without calling lock()

                        shouldThrow<AssertionError> { awaitLockCall(lock, writer, job) }
                            .message shouldBe "writer ended finished without calling lock()"

                        lock.unlock()
                    }
                }
            }
        }
        given("a held writer lock and writes queued one after another") {
            `when`("each write has taken its ticket before the next is launched") {
                then("the writes commit in launch order") {
                    runTest {
                        val lock = CountingMutex()
                        val order = mutableListOf<String>()
                        lock.lock() // the test holds the lock, so all three queue behind it
                        val writes =
                            listOf("first", "second", "third").map { name ->
                                queueWrite(lock, name) { lock.withLock { order += name } }
                            }

                        lock.unlock()

                        writes.forEach { it.await() }
                        order shouldBe listOf("first", "second", "third")
                    }
                }
            }
        }
    })
