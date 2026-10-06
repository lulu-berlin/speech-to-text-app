package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DatabaseSpec :
    BehaviorSpec({

        given("a segment stored for task 1, chunk 0, seq 0") {
            `when`("storing another segment with the same task, chunk_idx and seq") {
                then("it is rejected by the database; the same seq in another chunk is accepted") {
                    withDatabase { db ->
                        insertTask(db, 1)
                        execute(db, segmentInsert(chunkIdx = 0, seq = 0, text = "first"))

                        shouldThrow<java.sql.SQLException> {
                            execute(db, segmentInsert(chunkIdx = 0, seq = 0, text = "stored twice"))
                        }

                        execute(db, segmentInsert(chunkIdx = 1, seq = 0, text = "next chunk"))
                        rowCount(db, "segments").shouldBe(2)
                    }
                }
            }
        }

        given("an audio row for an existing task") {
            `when`("inserting another audio row with the same sha256 for a second task") {
                then("the insert is rejected by the database (at most one task per audio content)") {
                    withDatabase { db ->
                        insertTask(db, 1)
                        insertAudio(db, taskId = 1, sha256 = "aaaabbbbccccdddd", sizeBytes = 3)

                        insertTask(db, 2)
                        shouldThrow<java.sql.SQLException> {
                            insertAudio(db, taskId = 2, sha256 = "aaaabbbbccccdddd", sizeBytes = 3)
                        }
                    }
                }
            }
        }

        given("an empty data directory") {
            `when`("opening the database for the first time") {
                then("stt.db is created, V1 applies, and a reopen applies nothing and preserves rows") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()

                        Database.open(dataDir).use { first ->
                            tableNames(first).shouldContainAll(expectedTables)
                            migratedCount(first).shouldBe(1)
                            first.connection
                                .createStatement()
                                .use { st ->
                                    st.executeUpdate(
                                        "INSERT INTO tasks " +
                                            "(original_filename, content_type, status, speakers_mode, created_at) " +
                                            "VALUES ('a.wav', 'audio/wav', 'QUEUED', 'auto', 1)",
                                    )
                                }
                        }

                        Files.exists(dataDir.resolve("stt.db")).shouldBeTrue()

                        Database.open(dataDir).use { second ->
                            rowCount(second, "tasks").shouldBe(1)
                            migratedCount(second).shouldBe(1)
                        }
                    }
                }
            }
        }

        given("a data directory that cannot be created, or whose stt.db cannot be opened") {
            `when`("opening the database") {
                then("it fails with an error naming STT_DATA_DIR and the path") {
                    runTest {
                        val regularFile = tempdir("stt-db").toPath().resolve("data")
                        Files.writeString(regularFile, "not a directory")
                        val dbIsADirectory = tempdir("stt-db").toPath()
                        Files.createDirectory(dbIsADirectory.resolve("stt.db"))

                        for (dataDir in listOf(regularFile, dbIsADirectory)) {
                            val error = shouldThrowAny { Database.open(dataDir) }
                            error.message.shouldContain("STT_DATA_DIR")
                            error.message.shouldContain(dataDir.toString())
                        }
                    }
                }
            }
        }

        given("a write transaction held open on the writer connection, which then fails") {
            `when`("another write starts while it is open") {
                then("the other write waits for the lock; the failed transaction leaves nothing and only the other task is kept") {
                    runTest {
                        val lock = CountingMutex()
                        Database.open(tempdir("stt-db").toPath(), lock).use { db ->
                            coroutineScope {
                                val tasks = TaskRepository(db)
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
                                tasks.list().shouldBe(listOf(StoredTask(insert.await(), concurrent)))
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
                            val tasks = TaskRepository(db)
                            val stored = queuedTask("stored.wav", createdAt = 1L)
                            val id = tasks.createWithAudio(stored, "abc".byteInputStream())
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
                            val tasks = TaskRepository(db)
                            val stored = queuedTask("stored.wav", createdAt = 1L)
                            val id = tasks.createWithAudio(stored, "abc".byteInputStream())
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
                                val tasks = TaskRepository(db)
                                val source = StallingFailingSource()
                                val creation =
                                    async { runCatching { tasks.createWithAudio(queuedTask("failing.wav", createdAt = 1L), source) } }
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
                                Files.list(dataDir.resolve("tmp")).use { it.toList() }.shouldBeEmpty()
                            }
                        }
                    }
                }
            }
        }

        given("a database opened, closed, and opened again") {
            `when`("reading the journal mode and the foreign-key setting on each connection") {
                then("every connection is in WAL mode with foreign keys on") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        repeat(2) {
                            Database.open(dataDir).use { db ->
                                pragma(db, "journal_mode").shouldBe("wal")
                                pragma(db, "foreign_keys").shouldBe("1")
                            }
                        }
                    }
                }
            }
        }

        given("no task with id 42") {
            `when`("inserting a chunks, segments, audio, and audio_parts row for task 42") {
                then("each insert is rejected; the same rows are accepted once task 42 exists") {
                    withDatabase { db ->
                        for (sql in task42RowInserts) {
                            shouldThrow<java.sql.SQLException> { execute(db, sql) }
                        }

                        insertTask(db, 42)
                        for (sql in task42RowInserts) {
                            execute(db, sql)
                        }
                        for (table in listOf("chunks", "segments", "audio", "audio_parts")) {
                            rowCount(db, table).shouldBe(1)
                        }
                    }
                }
            }
        }

        given("task rows with an invalid status, an invalid phase, or an upper-case speakers_mode") {
            `when`("inserting them") {
                then("each is rejected; the same row with valid values is accepted") {
                    withDatabase { db ->
                        val invalid =
                            listOf(
                                Triple("DONE", "CHUNKING", "auto"),
                                Triple("QUEUED", "UPLOADING", "auto"),
                                Triple("QUEUED", "CHUNKING", "AUTO"),
                            )
                        for ((status, phase, speakersMode) in invalid) {
                            shouldThrow<java.sql.SQLException> { execute(db, taskInsert(status, phase, speakersMode)) }
                        }

                        execute(db, taskInsert("QUEUED", "CHUNKING", "auto"))
                        rowCount(db, "tasks").shouldBe(1)
                    }
                }
            }
        }

        given("a data directory path whose parent directories do not exist either") {
            `when`("opening the database") {
                then("the directories are created and stt.db is created inside") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath().resolve("missing/nested/data")

                        Database.open(dataDir).close()

                        Files.isDirectory(dataDir).shouldBeTrue()
                        Files.isRegularFile(dataDir.resolve("stt.db")).shouldBeTrue()
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

private val expectedTables =
    listOf(
        "tasks",
        "audio",
        "audio_parts",
        "chunks",
        "segments",
        "queue_state",
        "flyway_schema_history",
    )

private fun tableNames(db: Database): List<String> =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { names ->
            val tables = ArrayList<String>()
            while (names.next()) {
                tables.add(names.getString(1))
            }
            tables
        }
    }

private fun migratedCount(db: Database): Int = singleValue(db, "SELECT count(*) FROM flyway_schema_history").toInt()

private fun pragma(
    db: Database,
    name: String,
): String = singleValue(db, "PRAGMA $name")

private val task42RowInserts =
    listOf(
        "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (42, 0, 0, 1000, 'PENDING')",
        "INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text) VALUES (42, 0, 0, 0, 900, 'x')",
        "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (42, 1, 'aa')",
        "INSERT INTO audio_parts (task_id, part_no, part) VALUES (42, 0, x'00')",
    )

private fun execute(
    db: Database,
    sql: String,
) = db.connection.createStatement().use { statement -> statement.executeUpdate(sql) }

private fun segmentInsert(
    chunkIdx: Int,
    seq: Int,
    text: String,
) = "INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text) VALUES (1, $chunkIdx, $seq, 0, 900, '$text')"

private fun taskInsert(
    status: String,
    phase: String,
    speakersMode: String,
) = "INSERT INTO tasks (original_filename, content_type, status, phase, speakers_mode, created_at) " +
    "VALUES ('a.wav', 'audio/wav', '$status', '$phase', '$speakersMode', 1)"

private fun insertTask(
    db: Database,
    id: Int,
) = db.connection
    .prepareStatement(
        "INSERT INTO tasks (id, original_filename, content_type, status, speakers_mode, created_at) " +
            "VALUES (?, 'a.wav', 'audio/wav', 'QUEUED', 'auto', 1)",
    ).use { st ->
        st.setInt(1, id)
        st.executeUpdate()
    }

private fun insertAudio(
    db: Database,
    taskId: Int,
    sha256: String,
    sizeBytes: Int,
) = db.connection.prepareStatement("INSERT INTO audio (task_id, size_bytes, sha256) VALUES (?, ?, ?)").use { st ->
    st.setInt(1, taskId)
    st.setInt(2, sizeBytes)
    st.setString(3, sha256)
    st.executeUpdate()
}

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
