package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The writer lock, read connections, and upload spooling under concurrent use (ADR 0017, ADR 0018). */
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
                                val tasks = taskRepository(db, dataDir)
                                val heldWrite = holdWriteTransaction(db, queuedTask("held.wav", createdAt = 1L))

                                val concurrent = queuedTask("concurrent.wav", createdAt = 2L)
                                val insert = async { tasks.insert(concurrent) }
                                // The second lock() call can only come from the insert, while the held transaction
                                // owns the lock: from here on it waits. Without the lock this times out and fails.
                                withContext(Dispatchers.Default) { withTimeout(PROBE_TIMEOUT_MS) { lock.lockCalls.first { it == 2 } } }
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

        given("a stored task with audio, and a write transaction held open on the writer connection") {
            `when`("every repository read runs while the write is open") {
                then("each read completes and sees only the committed state") {
                    withDatabase { db ->
                        coroutineScope {
                            val tasks = taskRepository(db, dataDir)
                            val stored = queuedTask("stored.wav", createdAt = 1L)
                            val id = tasks.createFromUpload(stored, "abc".byteInputStream())
                            val heldWrite = holdWriteTransaction(db, queuedTask("uncommitted.wav", createdAt = 2L))

                            try {
                                // Real time: a read that waits for the writer never finishes inside the bound.
                                withContext(Dispatchers.Default) {
                                    withTimeout(PROBE_TIMEOUT_MS) {
                                        tasks.list().shouldBe(listOf(StoredTask(id, stored)))
                                        tasks.find(id).shouldBe(StoredTask(id, stored))
                                        ChunkRepository(db).findByTask(id).shouldBe(emptyList())
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
                                    }
                                }
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

        given("a stored task with audio whose copyTo is stalled on its target") {
            `when`("another task is inserted while the copy is stalled") {
                then("the insert completes; once released, the copy delivers the full audio") {
                    withDatabase { db ->
                        coroutineScope {
                            val tasks = taskRepository(db, dataDir)
                            val stored = queuedTask("stored.wav", createdAt = 1L)
                            // copyTo reads the pages AudioRepository.write stores; uploads no longer store any (ADR 0024).
                            val id = tasks.insert(stored)
                            AudioRepository(db).write(id, "abc".byteInputStream())
                            val target = StallingTarget()
                            val copy = async { AudioRepository(db).copyTo(id, target) }
                            withContext(Dispatchers.IO) { target.stalled.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue() }

                            val other = queuedTask("other.wav", createdAt = 2L)
                            val otherId =
                                try {
                                    // Real time: an insert that waits for the copy never finishes inside the bound.
                                    withContext(Dispatchers.Default) { withTimeout(PROBE_TIMEOUT_MS) { tasks.insert(other) } }
                                } finally {
                                    target.release.countDown()
                                }

                            copy.await()
                            target.bytes().decodeToString().shouldBe("abc")
                            tasks.list().shouldBe(listOf(StoredTask(id, stored), StoredTask(otherId, other)))
                        }
                    }
                }
            }
        }

        given("a task creation whose upload source delivers some bytes, then stalls, then fails") {
            `when`("another task is inserted while the source is stalled") {
                then("the insert completes; the failed creation leaves no task, pages, metadata, or temp file") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            coroutineScope {
                                val tasks = taskRepository(db, dataDir)
                                val source = StallingFailingSource()
                                val creation =
                                    async { runCatching { tasks.createFromUpload(queuedTask("failing.wav", createdAt = 1L), source) } }
                                withContext(Dispatchers.IO) {
                                    source.stalled.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue()
                                }

                                val other = queuedTask("other.wav", createdAt = 2L)
                                val otherId =
                                    try {
                                        // Real time: an insert that waits for the upload never finishes inside the bound.
                                        withContext(Dispatchers.Default) { withTimeout(PROBE_TIMEOUT_MS) { tasks.insert(other) } }
                                    } finally {
                                        source.release.countDown()
                                    }

                                creation.await().exceptionOrNull().shouldBeInstanceOf<IOException>()
                                tasks.list().shouldBe(listOf(StoredTask(otherId, other)))
                                rowCount(db, "audio_parts").shouldBe(0)
                                rowCount(db, "audio").shouldBe(0)
                                entries(dataDir.resolve("tmp")).shouldBeEmpty()
                            }
                        }
                    }
                }
            }
        }
    })

// Real-time bounds for the concurrency tests. A probe waits for something that must happen;
// a stall (held write, stalled target) blocks until the test releases it. The stall's safety
// bound must outlast every probe: otherwise a stall that gives up first would let a probe
// finish behind it, and a broken lock or a read routed through the writer would pass.
private const val PROBE_TIMEOUT_MS = 10_000L
private const val STALL_SAFETY_TIMEOUT_MS = 30_000L

/** A write transaction held open on the writer connection; once released it fails, so it rolls back. */
private class HeldWrite(
    private val releaseLatch: CountDownLatch,
    val outcome: Deferred<Result<Unit>>,
) {
    fun release() = releaseLatch.countDown()
}

/** Starts a write transaction that inserts [task] and stays open until released; returns once it is open. */
private suspend fun CoroutineScope.holdWriteTransaction(
    db: Database,
    task: Task,
): HeldWrite {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val outcome =
        async {
            runCatching {
                db.writeTransaction {
                    insertTaskRow(task)
                    held.countDown()
                    release.await(STALL_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    throw IOException("held transaction failed")
                }
            }
        }
    withContext(Dispatchers.IO) { held.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue() }
    return HeldWrite(release, outcome)
}

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

/** A copy target that keeps what it receives, signals [stalled] on its first write, and blocks until [release]. */
private class StallingTarget : OutputStream() {
    val stalled = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val received = ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        received.write(b, off, len)
        stalled.countDown()
        release.await(STALL_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun bytes(): ByteArray = received.toByteArray()
}

/** A [Mutex] that counts its lock() calls, so a test can see a caller start waiting for it. */
private class CountingMutex(
    private val delegate: Mutex = Mutex(),
) : Mutex by delegate {
    val lockCalls = MutableStateFlow(0)

    override suspend fun lock(owner: Any?) {
        lockCalls.update { it + 1 }
        delegate.lock(owner)
    }
}
