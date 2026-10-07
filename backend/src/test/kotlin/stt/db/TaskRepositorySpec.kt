package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import stt.queue.TaskStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

class TaskRepositorySpec :
    BehaviorSpec({

        given("a task with every field set and a task with every nullable field null") {
            `when`("both are saved through the repository and read back by id") {
                then("each reads back with identical fields") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val full =
                            Task(
                                originalFilename = "interview.m4a",
                                contentType = "audio/mp4",
                                durationMs = 61_000L,
                                status = TaskStatus.RUNNING,
                                phase = TaskPhase.TRANSCRIBING,
                                queuePosition = 3,
                                language = "de",
                                speakersMode = SpeakersMode.FIXED,
                                speakersCount = 2,
                                maxSpeakers = 4,
                                model = "base.en",
                                progress = 0.25,
                                error = "previous attempt failed",
                                createdAt = 1_700_000_000_000L,
                                startedAt = 1_700_000_001_000L,
                                finishedAt = 1_700_000_002_000L,
                            )
                        val sparse = queuedTask("memo.wav", createdAt = 1_700_000_003_000L)

                        val fullId = tasks.insert(full)
                        val sparseId = tasks.insert(sparse)

                        tasks.find(fullId).shouldBe(StoredTask(fullId, full))
                        tasks.find(sparseId).shouldBe(StoredTask(sparseId, sparse))
                    }
                }
            }
        }

        given("an upload of two full 1 MiB pages plus 12345 bytes") {
            `when`("a task is created from it") {
                then(
                    "no audio bytes are stored in the database, the original is byte-identical at work/<task-id>/source with no spool left, and size_bytes/sha256 match the upload",
                ) {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            val size = 2L * MIB + 12_345
                            val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))
                            val task = queuedTask("long.wav", createdAt = 1L)
                            val upload = SpoolWatchingSource(GeneratedBytes(size), db.tmpDir)

                            val taskId = tasks.createFromUpload(task, upload)

                            upload.largestUnspooled.shouldBeLessThanOrEqual(MIB.toLong())
                            tasks.find(taskId).shouldBe(StoredTask(taskId, task))
                            val original = dataDir.resolve("work").resolve(taskId.toString()).resolve("source")
                            Files.readAllBytes(original).shouldBe(GeneratedBytes(size).readAllBytes())
                            Files.list(db.tmpDir).use { it.toList() }.shouldBeEmpty()
                            // 2 × 1 048 576 + 12 345 = 2 109 497 bytes; the digest is the test's own, over a fresh stream.
                            AudioRepository(db)
                                .metadata(taskId)
                                .shouldBe(AudioMetadata(sizeBytes = 2_109_497L, sha256 = sha256Hex(GeneratedBytes(size))))
                            // Together the database files are smaller than the upload: no table holds a copy of it.
                            databaseFilesSize(dataDir).shouldBeLessThan(size)
                        }
                    }
                }
            }
        }

        given("a task created from an upload") {
            `when`("a second task is created from an upload of the same bytes under another filename") {
                then("creation fails and leaves no task, no metadata, no spool file and no work directory behind") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val first = queuedTask("take1.wav", createdAt = 1L)
                        val firstId = tasks.createFromUpload(first, "same bytes".byteInputStream())

                        shouldThrow<SQLException> {
                            tasks.createFromUpload(queuedTask("take2.wav", createdAt = 2L), "same bytes".byteInputStream())
                        }

                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        tasks.find(firstId).shouldBe(StoredTask(firstId, first))
                        Files.list(db.tmpDir).use { it.toList() }.shouldBeEmpty()
                        Files
                            .list(
                                dataDir.resolve("work"),
                            ).use { it.toList() }
                            .shouldBe(listOf(dataDir.resolve("work").resolve(firstId.toString())))
                    }
                }
            }
        }

        given("two tasks, each with audio, chunks, and segments") {
            `when`("the first task is deleted") {
                then("its audio, chunks, and segments are gone; the second task's rows remain") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val doomedId =
                            tasks.createFromUpload(queuedTask("doomed.wav", createdAt = 1L), "doomed".byteInputStream())
                        val keptId = tasks.createFromUpload(queuedTask("kept.wav", createdAt = 2L), "kept".byteInputStream())
                        for (taskId in listOf(doomedId, keptId)) {
                            chunks.insertAll(taskId, listOf(Chunk(idx = 0, startMs = 0L, endMs = 1_000L, status = ChunkStatus.DONE)))
                            segments.insertAll(
                                taskId,
                                listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 900L, text = "x", speaker = null)),
                            )
                        }

                        tasks.delete(doomedId)

                        tasks.find(doomedId).shouldBeNull()
                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        rowCount(db, "chunks").shouldBe(1)
                        rowCount(db, "segments").shouldBe(1)
                        tasks.find(keptId).shouldBe(StoredTask(keptId, queuedTask("kept.wav", createdAt = 2L)))
                    }
                }
            }
        }

        given("two tasks with audio, one of them two pages long, and the audio page table made unreadable") {
            `when`("listing tasks") {
                then("both tasks come back in creation order without touching audio pages") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val first = queuedTask("first.wav", createdAt = 1L)
                        val second = queuedTask("second.wav", createdAt = 2L)
                        val firstId = tasks.createFromUpload(first, ByteArray(1_048_577) { 7 }.inputStream())
                        val secondId = tasks.createFromUpload(second, "short".byteInputStream())
                        // Any read of audio pages now fails with "no such table".
                        db.connection.createStatement().use { st ->
                            st.execute("ALTER TABLE audio_parts RENAME TO audio_parts_hidden")
                        }

                        tasks.list().shouldBe(listOf(StoredTask(firstId, first), StoredTask(secondId, second)))
                    }
                }
            }
        }
    })

/** Bytes in `stt.db` plus its WAL: everything the database has written to disk. */
private fun databaseFilesSize(dataDir: Path): Long =
    listOf("stt.db", "stt.db-wal").sumOf { name -> dataDir.resolve(name).let { if (Files.exists(it)) Files.size(it) else 0L } }
