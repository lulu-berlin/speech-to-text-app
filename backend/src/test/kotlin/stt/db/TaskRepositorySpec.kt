package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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
                                error = "previous attempt failed",
                                createdAt = 1_700_000_000_000L,
                                startedAt = 1_700_000_001_000L,
                                finishedAt = 1_700_000_002_000L,
                            )
                        val sparse = queuedTask("memo.wav", createdAt = 1_700_000_003_000L)

                        val fullId = tasks.insert(full)
                        val sparseId = tasks.insert(sparse)

                        tasks.find(fullId).shouldBe(full)
                        tasks.find(sparseId).shouldBe(sparse)
                    }
                }
            }
        }

        given("a task created with some audio") {
            `when`("a second task is created with the same audio bytes under another filename") {
                then("creation fails and leaves no task, pages, or metadata behind") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val first = queuedTask("take1.wav", createdAt = 1L)
                        val firstId = tasks.createWithAudio(first, "same bytes".byteInputStream())

                        shouldThrow<SQLException> {
                            tasks.createWithAudio(queuedTask("take2.wav", createdAt = 2L), "same bytes".byteInputStream())
                        }

                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        rowCount(db, "audio_parts").shouldBe(1)
                        tasks.find(firstId).shouldBe(first)
                    }
                }
            }
        }

        given("two tasks, each with audio, chunks, and segments; the first with two audio pages") {
            `when`("the first task is deleted") {
                then("its audio, audio pages, chunks, and segments are gone; the second task's rows remain") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val doomedId =
                            tasks.createWithAudio(queuedTask("doomed.wav", createdAt = 1L), ByteArray(1_048_577) { 1 }.inputStream())
                        val keptId = tasks.createWithAudio(queuedTask("kept.wav", createdAt = 2L), "kept".byteInputStream())
                        for (taskId in listOf(doomedId, keptId)) {
                            chunks.insertAll(taskId, listOf(Chunk(idx = 0, startMs = 0L, endMs = 1_000L, status = ChunkStatus.DONE)))
                            segments.insertAll(
                                taskId,
                                listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 900L, text = "x", speaker = null)),
                            )
                        }

                        tasks.delete(doomedId)

                        tasks.find(doomedId).shouldBe(null)
                        rowCount(db, "tasks").shouldBe(1)
                        rowCount(db, "audio").shouldBe(1)
                        rowCount(db, "audio_parts").shouldBe(1)
                        rowCount(db, "chunks").shouldBe(1)
                        rowCount(db, "segments").shouldBe(1)
                        tasks.find(keptId).shouldBe(queuedTask("kept.wav", createdAt = 2L))
                    }
                }
            }
        }
    })
