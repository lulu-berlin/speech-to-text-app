package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import stt.audio.PlannedChunk
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The writer lock, read connections, and upload staging under concurrent use (ADR 0017, ADR 0024). */
class ConcurrencySpec :
    BehaviorSpec({

        given("a write transaction held open on the writer connection, which then fails") {
            `when`("another write starts while it is open") {
                then("the other write waits for the lock; the failed transaction leaves nothing and only the other task is kept") {
                    runTest {
                        val lock = CountingMutex()
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir, lock).use { db ->
                            coroutineScope {
                                val tasks = TaskRepository(db)
                                val heldWrite = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 1L))

                                val concurrent = queuedTask("concurrent.wav", createdAt = 2L)
                                // Hold the insert's launch past the probe's old 10 s bound: the probe is tied
                                // to this insert's own writer, so a write starved that long is still awaited.
                                val writer = LockWriter("insert")
                                val insertGate = CompletableDeferred<Unit>()
                                val insert =
                                    async(Dispatchers.Default + writer) {
                                        insertGate.await()
                                        tasks.insert(concurrent)
                                    }
                                backgroundScope.launch(Dispatchers.Default) {
                                    delay(11.seconds)
                                    insertGate.complete(Unit)
                                }
                                // The insert waits behind the held transaction once it reaches the lock;
                                // the wait is tied to its own writer, never to a lock-call count or a clock.
                                awaitLockCall(lock, writer, insert)
                                insert.isCompleted.shouldBeFalse()
                                heldWrite.release()

                                heldWrite.outcome
                                    .await()
                                    .isFailure
                                    .shouldBeTrue()
                                // Reads no longer queue behind writes: await the insert's commit before listing.
                                val concurrentId = insert.await()
                                tasks.list().shouldBe(listOf(StoredTask(concurrentId, concurrent)))
                            }
                        }
                    }
                }
            }
        }

        given("a stored task with audio and a stored one-chunk plan, and a write transaction held open on the writer connection") {
            `when`("every repository read runs while the write is open") {
                then("each read completes and sees only the committed state") {
                    withDatabase { db ->
                        coroutineScope {
                            val tasks = TaskRepository(db)
                            val chunks = ChunkRepository(db)
                            val stored = queuedTask("stored.wav", createdAt = 1L)
                            val id = tasks.createFromUpload(stored, "abc".byteInputStream())
                            storePlan(db, id, listOf(PlannedChunk(0L, 1_000L)))
                            val heldWrite = holdWriteTransaction(db, queuedTask("uncommitted.wav", createdAt = 2L))

                            try {
                                // Reads use their own connections: a read that waited for the writer would
                                // hang here and fail through runTest's own timeout.
                                tasks.list().shouldBe(listOf(StoredTask(id, stored)))
                                tasks.find(id).shouldBe(StoredTask(id, stored))
                                chunks
                                    .findByTask(id)
                                    .shouldBe(listOf(Chunk(idx = 0, startMs = 0L, endMs = 1_000L, status = ChunkStatus.PENDING)))
                                SegmentRepository(db).findByTask(id).shouldBe(emptyList())
                                // sha256("abc"): the FIPS 180-2 test vector.
                                AudioRepository(db)
                                    .metadata(id)
                                    .shouldBe(
                                        AudioMetadata(
                                            sizeBytes = 3L,
                                            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                                        ),
                                    )
                                QueueStateRepository(db).isPaused().shouldBeFalse()
                            } finally {
                                heldWrite.release()
                            }
                            heldWrite.outcome
                                .await()
                                .isFailure
                                .shouldBeTrue()
                        }
                    }
                }
            }
        }

        given("a task creation whose upload source delivers some bytes, then stalls, then fails") {
            `when`("another task is inserted while the source is stalled") {
                then("the insert completes; the failed creation leaves no task, metadata, or temp file") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            coroutineScope {
                                val tasks = TaskRepository(db)
                                val source = StallingFailingSource()
                                val creation =
                                    async { runCatching { tasks.createFromUpload(queuedTask("failing.wav", createdAt = 1L), source) } }
                                withContext(Dispatchers.IO) {
                                    source.stalled.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue()
                                }

                                val other = queuedTask("other.wav", createdAt = 2L)
                                val otherId =
                                    try {
                                        // An insert that waited for the upload would hang here and fail through
                                        // runTest's own timeout; the insert itself has no clock.
                                        tasks.insert(other)
                                    } finally {
                                        source.release.countDown()
                                    }

                                creation.await().exceptionOrNull().shouldBeInstanceOf<IOException>()
                                tasks.list().shouldBe(listOf(StoredTask(otherId, other)))
                                rowCount(db, "audio").shouldBe(0)
                                entries(dataDir.resolve("tmp")).shouldBeEmpty()
                            }
                        }
                    }
                }
            }
        }
    })

// The stalled source's waits: the test waits for the stall signal, and the source blocks until released
// or its safety bound expires. The insert under test has no clock; a wrong block fails through
// runTest's own timeout, and the stall's safety bound outlasts every wait that must happen first.
private const val PROBE_TIMEOUT_MS = 10_000L
private const val STALL_SAFETY_TIMEOUT_MS = 30_000L

/** An upload source that delivers "abc", then signals [stalled] on its next read, blocks until [release], and fails. */
private class StallingFailingSource : InputStream() {
    val stalled = CountDownLatch(1)
    val release = CountDownLatch(1)
    private var delivered = false

    override fun read(): Int = throw UnsupportedOperationException("read in windows")

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (!delivered) {
            delivered = true
            "abc".toByteArray().copyInto(b, off)
            return 3
        }
        stalled.countDown()
        release.await(STALL_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        throw IOException("audio source failed")
    }
}
