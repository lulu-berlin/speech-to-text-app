package stt.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

/**
 * The writer-lock probe of the race specs (roadmap item 55): a wait for a queued write is tied to its own
 * writer, so neither a conflated call count nor another writer's lock call can decide it.
 */
class CountingMutexSpec :
    BehaviorSpec({
        given("a held writer lock with two writers queued behind it") {
            `when`("both have called lock() before either wait starts, so an exact call count is already past the first writer's") {
                then("each wait completes for its own writer, and a wait for a writer that has not called lock() times out naming it") {
                    runTest {
                        val lock = CountingMutex()
                        lock.lock()
                        val first = LockWriter("first")
                        val second = LockWriter("second")
                        val notYet = LockWriter("not-yet")
                        val notYetMayLock = CompletableDeferred<Unit>()
                        launch(first) { lock.withLock {} }
                        launch(second) { lock.withLock {} }
                        launch(notYet) {
                            notYetMayLock.await()
                            lock.withLock {}
                        }
                        // Both queued writers are inside lock() before any wait starts: a call count would already
                        // have jumped past the first writer's number, which an exact-count wait would miss.
                        runCurrent()
                        lock.writers.value shouldBe setOf(first, second)

                        awaitLockCall(lock, first)
                        awaitLockCall(lock, second)
                        // The two queued writers' calls are already recorded, so a wait they could satisfy would
                        // return at once; a short bound is enough to show they do not.
                        shouldThrow<AssertionError> { awaitLockCall(lock, notYet, timeout = 200.milliseconds) }
                            .message shouldContain "not-yet"

                        notYetMayLock.complete(Unit)
                        lock.unlock()
                    }
                }
            }
        }
    })
