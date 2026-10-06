package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DatabaseSpec :
    BehaviorSpec({

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

        given("a task creation whose audio source stalls inside its transaction, then fails") {
            `when`("another task is inserted while that transaction is open") {
                then("the failed creation leaves nothing behind and only the other task is kept") {
                    withDatabase { db ->
                        coroutineScope {
                            val tasks = TaskRepository(db)
                            val stalled = CountDownLatch(1)
                            val release = CountDownLatch(1)
                            val creation =
                                async {
                                    runCatching {
                                        tasks.createWithAudio(
                                            queuedTask("failing.wav", createdAt = 1L),
                                            StallingFailingSource(stalled, release),
                                        )
                                    }
                                }
                            withContext(Dispatchers.IO) { stalled.await(10, TimeUnit.SECONDS).shouldBeTrue() }

                            val concurrent = queuedTask("concurrent.wav", createdAt = 2L)
                            val insert = async { tasks.insert(concurrent) }
                            // Real time (runTest's virtual clock would skip it): the insert finishes now unless held back.
                            withContext(Dispatchers.Default) { withTimeoutOrNull(500) { insert.join() } }
                            release.countDown()

                            creation.await().isFailure.shouldBeTrue()
                            tasks.list().shouldBe(listOf(StoredTask(insert.await(), concurrent)))
                            rowCount(db, "audio_parts").shouldBe(0)
                            rowCount(db, "audio").shouldBe(0)
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
    })

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

/** An audio source that signals [stalled] on its first read, waits for [release], then fails. */
private class StallingFailingSource(
    private val stalled: CountDownLatch,
    private val release: CountDownLatch,
) : InputStream() {
    override fun read(): Int {
        stalled.countDown()
        release.await(10, TimeUnit.SECONDS)
        throw IOException("audio source failed")
    }
}
