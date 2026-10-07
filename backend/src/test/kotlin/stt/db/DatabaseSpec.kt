package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.ArrayList

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
                then("stt.db is created, V1 and V2 apply, and a reopen applies nothing and preserves rows") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()

                        Database.open(dataDir).use { first ->
                            tableNames(first).shouldContainAll(expectedTables)
                            migratedCount(first).shouldBe(2)
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
                            migratedCount(second).shouldBe(2)
                        }
                    }
                }
            }
        }

        given("a database migrated to V1 only, holding a task with its audio row, a chunk, and an audio page") {
            `when`("the database is opened") {
                then("V2 applies: audio_parts is gone, chunk_audio exists, and the task, its audio row and its chunk are kept") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        val url = "jdbc:sqlite:${dataDir.resolve("stt.db")}"
                        Flyway
                            .configure()
                            .dataSource(url, null, null)
                            .target("1")
                            .load()
                            .migrate()
                        DriverManager.getConnection(url).use { connection ->
                            connection.createStatement().use { st -> v1Rows.forEach { sql -> st.executeUpdate(sql) } }
                        }

                        Database.open(dataDir).use { db ->
                            tableNames(db).shouldNotContain("audio_parts")
                            tableNames(db).shouldContainAll("chunk_audio")
                            taskRepository(db, dataDir).find(1L).shouldBe(StoredTask(1L, queuedTask("kept.wav", createdAt = 1L)))
                            AudioRepository(db).metadata(1L).shouldBe(AudioMetadata(sizeBytes = 3L, sha256 = "aa"))
                            ChunkRepository(db)
                                .findByTask(1L)
                                .shouldBe(listOf(Chunk(idx = 0, startMs = 0L, endMs = 1_000L, status = ChunkStatus.PENDING)))
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
                            shouldFailOpeningNaming(dataDir)
                        }
                    }
                }
            }
        }

        given("an existing stt.db in rollback-journal mode that is write-protected, so WAL cannot be enabled") {
            `when`("opening the database") {
                then("it fails with an error naming STT_DATA_DIR and the path") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        val dbFile = dataDir.resolve("stt.db")
                        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { connection ->
                            connection.createStatement().use { st -> st.execute("CREATE TABLE existing (x INTEGER)") }
                        }
                        Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("r--r--r--"))

                        shouldFailOpeningNaming(dataDir)
                    }
                }
            }
        }

        given("an existing stt.db whose recorded V1 checksum no longer matches V1__init.sql") {
            `when`("opening the database") {
                then("the failing migration fails opening with an error naming STT_DATA_DIR and the path") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            execute(db, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'")
                        }

                        shouldFailOpeningNaming(dataDir)
                    }
                }
            }
        }

        given("a database opened, closed, and opened again") {
            `when`("reading the journal mode, the foreign-key setting, and the WAL size limit on each connection") {
                then("every connection is in WAL mode with foreign keys on and a 64 MiB WAL size limit") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        repeat(2) {
                            Database.open(dataDir).use { db ->
                                pragma(db, "journal_mode").shouldBe("wal")
                                pragma(db, "foreign_keys").shouldBe("1")
                                // 64 MiB (ADR 0017) = 64 × 1024 × 1024 bytes.
                                pragma(db, "journal_size_limit").shouldBe("67108864")
                            }
                        }
                    }
                }
            }
        }

        given("a database whose WAL size limit is 16 KiB (four 4 KiB SQLite pages)") {
            `when`(
                "a task whose error is 1 MiB + 1 character long grows the WAL, a PASSIVE checkpoint completes, and a one-page write follows",
            ) {
                then("the WAL file shrinks back to at most the limit") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        val limit = 16L * 1024
                        val payloadSize = 1_048_577
                        val wal = dataDir.resolve("stt.db-wal")
                        Database.open(dataDir, Mutex(), journalSizeLimit = limit).use { db ->
                            taskRepository(
                                db,
                                dataDir,
                            ).insert(queuedTask("large.wav", createdAt = 1L).copy(error = "x".repeat(payloadSize)))
                            // Every byte of the row is in a WAL frame until a checkpoint copies it into stt.db.
                            Files.size(wal).shouldBeGreaterThanOrEqual(payloadSize.toLong())

                            db.connection.createStatement().use { st ->
                                st.executeQuery("PRAGMA wal_checkpoint(PASSIVE)").use { rows ->
                                    rows.next().shouldBeTrue()
                                    // busy = 0 and every WAL frame checkpointed: the next write resets the WAL.
                                    rows.getInt(1).shouldBe(0)
                                    rows.getInt(3).shouldBe(rows.getInt(2))
                                }
                            }
                            QueueStateRepository(db).setPaused(true)

                            Files.size(wal).shouldBeLessThanOrEqual(limit)
                        }
                    }
                }
            }
        }

        given("no task with id 42") {
            `when`("inserting a chunks, segments, and audio row for task 42") {
                then("each insert is rejected; the same rows are accepted once task 42 exists") {
                    withDatabase { db ->
                        for (sql in task42RowInserts) {
                            shouldThrow<java.sql.SQLException> { execute(db, sql) }
                        }

                        insertTask(db, 42)
                        for (sql in task42RowInserts) {
                            execute(db, sql)
                        }
                        for (table in listOf("chunks", "segments", "audio")) {
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

        given("a data directory whose tmp/ holds files left by a crashed upload, next to another file") {
            `when`("opening the database") {
                then("the files in tmp/ are removed and tmp/ stays; files outside tmp/ are kept") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        val tmpDir = Files.createDirectories(dataDir.resolve("tmp"))
                        Files.writeString(tmpDir.resolve("upload-1.part"), "stale")
                        Files.writeString(tmpDir.resolve("upload-2.part"), "stale")
                        Files.writeString(dataDir.resolve("keep.txt"), "not a temp file")

                        Database.open(dataDir).close()

                        entries(tmpDir).shouldBeEmpty()
                        Files.readString(dataDir.resolve("keep.txt")).shouldBe("not a temp file")
                    }
                }
            }
        }
    })

/** A task, its audio row, a chunk, and an audio page, as a database at V1 holds them. */
private val v1Rows =
    listOf(
        "INSERT INTO tasks (id, original_filename, content_type, status, speakers_mode, created_at) " +
            "VALUES (1, 'kept.wav', 'audio/wav', 'QUEUED', 'auto', 1)",
        "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 3, 'aa')",
        "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 1000, 'PENDING')",
        "INSERT INTO audio_parts (task_id, part_no, part) VALUES (1, 0, x'616263')",
    )

private val expectedTables =
    listOf(
        "tasks",
        "audio",
        "chunk_audio",
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

/** Opening the database at [dataDir] fails with an error naming STT_DATA_DIR and the path. */
private suspend fun shouldFailOpeningNaming(dataDir: Path) {
    val error = shouldThrowAny { Database.open(dataDir) }
    error.message.shouldContain("STT_DATA_DIR")
    error.message.shouldContain(dataDir.toString())
}

private fun pragma(
    db: Database,
    name: String,
): String = singleValue(db, "PRAGMA $name")

private val task42RowInserts =
    listOf(
        "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (42, 0, 0, 1000, 'PENDING')",
        "INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text) VALUES (42, 0, 0, 0, 900, 'x')",
        "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (42, 1, 'aa')",
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
