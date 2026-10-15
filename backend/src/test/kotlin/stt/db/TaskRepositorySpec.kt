package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import stt.audio.PlannedChunk
import stt.queue.TaskStatus
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.sql.SQLException

class TaskRepositorySpec :
    BehaviorSpec({

        given("a task with every field set and a task with every nullable field null") {
            `when`("both are saved through the repository and read back by id") {
                then("each reads back with identical fields") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
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
                                diarizationStage = DiarizationStage.CLUSTERING,
                                diarizationProgress = 0.37,
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
                    "the original is stored in the database byte-identical, in windows of at most 1 MiB, no file is written, and size_bytes/sha256 match the upload",
                ) {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            val size = 2L * MIB + 12_345
                            val tasks = TaskRepository(db)
                            val task = queuedTask("long.wav", createdAt = 1L)
                            val upload = StagingWatchingSource(GeneratedBytes(size), db)

                            val taskId = tasks.createFromUpload(task, upload)

                            upload.largestUnstaged.shouldBeLessThanOrEqual(MIB.toLong())
                            tasks.find(taskId).shouldBe(StoredTask(taskId, task))
                            val original = ByteArrayOutputStream()
                            AudioRepository(db).copyOriginalTo(taskId, original)
                            original.toByteArray().shouldBe(GeneratedBytes(size).readAllBytes())
                            entries(dataDir.resolve("work")).shouldBeEmpty()
                            // 2 × 1 048 576 + 12 345 = 2 109 497 bytes; the digest is the test's own, over a fresh stream.
                            AudioRepository(db)
                                .metadata(taskId)
                                .shouldBe(AudioMetadata(sizeBytes = 2_109_497L, sha256 = sha256Hex(GeneratedBytes(size))))
                        }
                    }
                }
            }
        }

        given("a task created from an upload") {
            `when`("a second task is created from an upload of the same bytes under another filename") {
                then("creation fails and leaves no task, no metadata, no staged original and no work directory behind") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val first = queuedTask("take1.wav", createdAt = 1L)
                        val firstId = tasks.createFromUpload(first, "same bytes".byteInputStream())

                        shouldThrow<SQLException> {
                            tasks.createFromUpload(queuedTask("take2.wav", createdAt = 2L), "same bytes".byteInputStream())
                        }

                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        tasks.find(firstId).shouldBe(StoredTask(firstId, first))
                        rowCount(db, "original_audio").shouldBe(1)
                        entries(dataDir.resolve("work")).shouldBeEmpty()
                    }
                }
            }
        }

        given("an upload whose source delivers one full 1 MiB page and then fails") {
            `when`("a task is created from it") {
                then("creation fails with the source's error and leaves no task, no metadata, no spool file and no work directory") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)

                        shouldThrow<IOException> {
                            tasks.createFromUpload(
                                queuedTask("broken.wav", createdAt = 1L),
                                FailingAfter(MIB.toLong()),
                            )
                        }

                        rowCount(db, "tasks").shouldBe(0)
                        rowCount(db, "audio").shouldBe(0)
                        entries(dataDir.resolve("work")).shouldBeEmpty()
                    }
                }
            }
        }

        given("two tasks, each with audio, a chunk plan with its stored clip, and segments") {
            `when`("the first task is deleted") {
                then("its audio, chunks, clip and segments are gone; the second task's rows remain") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val doomedId =
                            tasks.createFromUpload(queuedTask("doomed.wav", createdAt = 1L), "doomed".byteInputStream())
                        val keptId = tasks.createFromUpload(queuedTask("kept.wav", createdAt = 2L), "kept".byteInputStream())
                        for (taskId in listOf(doomedId, keptId)) {
                            chunks.storeChunked(
                                taskId,
                                listOf(PlannedChunk(0L, 1_000L)),
                                header = "header".toByteArray(),
                                rows = listOf(CanonicalAudio(second = 0, frames = 4, bytes = "frames".toByteArray())),
                            )
                            segments.insertAll(
                                taskId,
                                listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 900L, text = "x", speakerId = null)),
                            )
                        }
                        rowCount(db, "audio_header").shouldBe(2)
                        rowCount(db, "audio_second").shouldBe(2)

                        tasks.delete(doomedId)

                        tasks.find(doomedId).shouldBeNull()
                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        rowCount(db, "chunks").shouldBe(1)
                        rowCount(db, "audio_header").shouldBe(1)
                        rowCount(db, "audio_second").shouldBe(1)
                        rowCount(db, "segments").shouldBe(1)
                        tasks.find(keptId).shouldBe(StoredTask(keptId, queuedTask("kept.wav", createdAt = 2L)))
                    }
                }
            }
        }

        given("two diarized tasks, each with two segments of two speakers") {
            `when`("the first task is deleted") {
                then("its segments and speakers are gone; the second task's segments and speakers remain") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val segments = SegmentRepository(db)
                        val doomedId = tasks.insert(queuedTask("doomed.wav", createdAt = 1L))
                        val keptId = tasks.insert(queuedTask("kept.wav", createdAt = 2L))
                        val transcribed =
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 900L, text = "x", speakerId = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 900L, endMs = 1_800L, text = "y", speakerId = null),
                            )
                        for (taskId in listOf(doomedId, keptId)) {
                            segments.insertAll(taskId, transcribed)
                            segments.storeSpeakers(taskId, speakerIds = listOf(0, 1))
                        }

                        tasks.delete(doomedId)

                        segments.findByTask(doomedId).shouldBe(emptyList())
                        segments.findSpeakers(doomedId).shouldBe(emptyList())
                        segments.findByTask(keptId).map { it.speakerId }.shouldBe(listOf(0, 1))
                        segments.findSpeakers(keptId).map { it.speakerId }.shouldBe(listOf(0, 1))
                    }
                }
            }
        }

        given("a QUEUED, a CANCELED, a PAUSED and a COMPLETED task") {
            `when`("the worker's start write is tried on each") {
                then("only the QUEUED one becomes RUNNING with its start time; the others are left exactly as they were") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val ids =
                            listOf(TaskStatus.QUEUED, TaskStatus.CANCELED, TaskStatus.PAUSED, TaskStatus.COMPLETED)
                                .associateWith { status -> tasks.insert(queuedTask("$status.wav", createdAt = 1).copy(status = status)) }
                        val before = ids.mapValues { (_, id) -> tasks.find(id) }

                        ids.mapValues { (_, id) -> tasks.setRunningIfQueued(id, startedAt = 42) } shouldBe
                            mapOf(
                                TaskStatus.QUEUED to true,
                                TaskStatus.CANCELED to false,
                                TaskStatus.PAUSED to false,
                                TaskStatus.COMPLETED to false,
                            )

                        val started = tasks.find(ids.getValue(TaskStatus.QUEUED))?.task
                        started?.status shouldBe TaskStatus.RUNNING
                        started?.startedAt shouldBe 42L
                        for (status in listOf(TaskStatus.CANCELED, TaskStatus.PAUSED, TaskStatus.COMPLETED)) {
                            tasks.find(ids.getValue(status)) shouldBe before.getValue(status)
                        }
                    }
                }
            }
        }
    })
