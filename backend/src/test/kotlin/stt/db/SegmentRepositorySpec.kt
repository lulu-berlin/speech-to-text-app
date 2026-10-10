package stt.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.audio.PlannedChunk

class SegmentRepositorySpec :
    BehaviorSpec({

        given("segments saved for a task out of order, plus a segment of another task") {
            `when`("reading the task's segments back") {
                then("they come back in seq order, only for that task") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))

                        segments.insertAll(
                            taskId,
                            listOf(
                                Segment(chunkIdx = 1, seq = 1, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speakerId = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speakerId = null),
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speakerId = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 44_000L, text = "So.", speakerId = null),
                            ),
                        )
                        segments.insertAll(
                            otherTaskId,
                            listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 4_000L, text = "Other.", speakerId = null)),
                        )

                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speakerId = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speakerId = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 44_000L, text = "So.", speakerId = null),
                                Segment(chunkIdx = 1, seq = 1, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speakerId = null),
                            ),
                        )
                    }
                }
            }
        }

        given("segments whose seq restarts in every chunk, saved out of order") {
            `when`("reading the task's segments back") {
                then("they come back in (chunk_idx, seq) order") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))

                        segments.insertAll(
                            taskId,
                            listOf(
                                Segment(chunkIdx = 1, seq = 1, startMs = 40_000L, endMs = 50_000L, text = "D", speakerId = null),
                                Segment(chunkIdx = 0, seq = 2, startMs = 20_000L, endMs = 29_000L, text = "C", speakerId = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 39_000L, text = "B2", speakerId = null),
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 9_000L, text = "A", speakerId = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 10_000L, endMs = 19_000L, text = "B", speakerId = null),
                            ),
                        )

                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 9_000L, text = "A", speakerId = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 10_000L, endMs = 19_000L, text = "B", speakerId = null),
                                Segment(chunkIdx = 0, seq = 2, startMs = 20_000L, endMs = 29_000L, text = "C", speakerId = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 39_000L, text = "B2", speakerId = null),
                                Segment(chunkIdx = 1, seq = 1, startMs = 40_000L, endMs = 50_000L, text = "D", speakerId = null),
                            ),
                        )
                    }
                }
            }
        }

        given("a task with a PENDING chunk 1") {
            `when`("the chunk is completed with its segments, once validly and once with a segment the database rejects") {
                then("a chunk's segments and its DONE status are stored together or not at all") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val failingTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))
                        chunks.storePlan(taskId, exportedChunks(dataDir, "talk", PlannedChunk(0L, 30_000L), PlannedChunk(30_000L, 60_000L)))
                        chunks.storePlan(
                            failingTaskId,
                            exportedChunks(dataDir, "other", PlannedChunk(0L, 30_000L), PlannedChunk(30_000L, 60_000L)),
                        )
                        // Chunk 0 was transcribed earlier; raw SQL in a committed write transaction stands in for that.
                        db.writeTransaction { exec("UPDATE chunks SET status = 'DONE' WHERE idx = 0") }

                        val transcribed =
                            listOf(
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 37_940L, text = " So.", speakerId = null),
                                Segment(chunkIdx = 1, seq = 1, startMs = 37_940L, endMs = 40_760L, text = " Bye.", speakerId = null),
                            )
                        segments.completeChunk(taskId, chunkIdx = 1, transcribed)

                        chunks.findByTask(taskId).map { it.status }.shouldBe(listOf(ChunkStatus.DONE, ChunkStatus.DONE))
                        segments.findByTask(taskId).shouldBe(transcribed)

                        // start_ms = -1 violates CHECK (start_ms >= 0), so the second insert fails after the status update.
                        val rejected = listOf(transcribed[0], transcribed[1].copy(startMs = -1L))
                        shouldThrowAny { segments.completeChunk(failingTaskId, chunkIdx = 1, rejected) }

                        chunks.findByTask(failingTaskId).map { it.status }.shouldBe(listOf(ChunkStatus.DONE, ChunkStatus.PENDING))
                        segments.findByTask(failingTaskId).shouldBe(emptyList())
                    }
                }
            }
        }

        given("a task whose plan has only chunk 0") {
            `when`("chunk 5 is completed") {
                then("completing a chunk that is not in the plan fails and stores nothing") {
                    withDatabase { db ->
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = taskRepository(db, dataDir).insert(queuedTask("talk.wav", createdAt = 1L))
                        chunks.storePlan(taskId, exportedChunks(dataDir, "talk", PlannedChunk(0L, 30_000L)))
                        val plan = listOf(Chunk(idx = 0, startMs = 0L, endMs = 30_000L, status = ChunkStatus.PENDING))

                        val orphan = Segment(chunkIdx = 5, seq = 0, startMs = 150_000L, endMs = 152_000L, text = " Lost.", speakerId = null)
                        shouldThrowAny { segments.completeChunk(taskId, chunkIdx = 5, listOf(orphan)) }

                        segments.findByTask(taskId).shouldBe(emptyList())
                        chunks.findByTask(taskId).shouldBe(plan)
                    }
                }
            }
        }

        given("a task with three segments over two chunks, and another task with one segment") {
            `when`("its speakers are stored, first with one speaker id too few, then with one per segment") {
                then("the short list stores nothing; the full list stores every segment's speaker id and the speakers as 'Speaker N'") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))
                        segments.insertAll(taskId, threeSegments)
                        val other = Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 4_000L, text = "Other.", speakerId = null)
                        segments.insertAll(otherTaskId, listOf(other))

                        shouldThrowAny { segments.storeSpeakers(taskId, speakerIds = listOf(0, 1)) }

                        segments.findByTask(taskId).shouldBe(threeSegments)
                        segments.findSpeakers(taskId).shouldBe(emptyList())

                        segments.storeSpeakers(taskId, speakerIds = listOf(0, 1, 0))

                        segments.findByTask(taskId).map { it.speakerId }.shouldBe(listOf(0, 1, 0))
                        segments.findSpeakers(taskId).shouldBe(speakers1And2)
                        segments.findByTask(otherTaskId).shouldBe(listOf(other))
                        segments.findSpeakers(otherTaskId).shouldBe(emptyList())
                    }
                }
            }
        }

        given("a task with three segments whose speakers were stored as [0, 1, 0]") {
            `when`("its speakers are stored again, as [0, 0, 1] and then as [0, 0, 0]") {
                then("each store replaces the previous speaker ids and speakers; a speaker no longer used is gone") {
                    withDatabase { db ->
                        val segments = SegmentRepository(db)
                        val taskId = taskRepository(db, dataDir).insert(queuedTask("talk.wav", createdAt = 1L))
                        segments.insertAll(taskId, threeSegments)
                        segments.storeSpeakers(taskId, speakerIds = listOf(0, 1, 0))

                        segments.storeSpeakers(taskId, speakerIds = listOf(0, 0, 1))

                        segments.findByTask(taskId).map { it.speakerId }.shouldBe(listOf(0, 0, 1))
                        segments.findSpeakers(taskId).shouldBe(speakers1And2)

                        segments.storeSpeakers(taskId, speakerIds = listOf(0, 0, 0))

                        segments.findByTask(taskId).map { it.speakerId }.shouldBe(listOf(0, 0, 0))
                        segments.findSpeakers(taskId).shouldBe(listOf(Speaker(speakerId = 0, speaker = "Speaker 1")))
                    }
                }
            }
        }

        given("a task with three segments whose speakers were stored as [0, 1, 0], and another task with speaker 1") {
            `when`("speaker 1 of the first task is renamed to Alice") {
                then("only that speaker's name changes: the segments and every other speaker are untouched") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))
                        segments.insertAll(taskId, threeSegments)
                        segments.storeSpeakers(taskId, speakerIds = listOf(0, 1, 0))
                        val other = Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 4_000L, text = "Other.", speakerId = null)
                        segments.insertAll(otherTaskId, listOf(other))
                        segments.storeSpeakers(otherTaskId, speakerIds = listOf(1))
                        val storedSegments = segments.findByTask(taskId)

                        segments.renameSpeaker(taskId, speakerId = 1, name = "Alice")

                        segments.findSpeakers(taskId).shouldBe(
                            listOf(
                                Speaker(speakerId = 0, speaker = "Speaker 1"),
                                Speaker(speakerId = 1, speaker = "Alice"),
                            ),
                        )
                        // A rename touches no segment row: the segments read back exactly as before.
                        segments.findByTask(taskId).shouldBe(storedSegments)
                        // The other task's speaker 1 is renamed in no task but its own.
                        segments.findSpeakers(otherTaskId).shouldBe(listOf(Speaker(speakerId = 1, speaker = "Speaker 2")))
                    }
                }
            }
        }

        given("task T with speaker 0 only, and task U without speakers") {
            `when`("segments that name a speaker are inserted") {
                then("the database rejects a speaker id the segment's own task does not have, and accepts one it has") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val segments = SegmentRepository(db)
                        val taskT = tasks.insert(queuedTask("t.wav", createdAt = 1L))
                        val taskU = tasks.insert(queuedTask("u.wav", createdAt = 2L))
                        segments.insertAll(taskT, threeSegments)
                        segments.storeSpeakers(taskT, speakerIds = listOf(0, 0, 0))
                        val laterSegment =
                            Segment(chunkIdx = 2, seq = 0, startMs = 60_000L, endMs = 62_000L, text = "Later.", speakerId = 0)

                        // T's speaker 0 is not U's: the foreign key is (task_id, speaker_id).
                        shouldThrowAny { segments.insertAll(taskU, listOf(laterSegment)) }
                        segments.findByTask(taskU).shouldBe(emptyList())
                        shouldThrowAny { segments.insertAll(taskT, listOf(laterSegment.copy(speakerId = 1))) }
                        segments.findByTask(taskT).map { it.speakerId }.shouldBe(listOf(0, 0, 0))

                        segments.insertAll(taskT, listOf(laterSegment))
                        segments.findByTask(taskT).map { it.speakerId }.shouldBe(listOf(0, 0, 0, 0))
                    }
                }
            }
        }
    })

/** Three segments of a task over chunks 0 and 1, not yet diarized. */
private val threeSegments =
    listOf(
        Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speakerId = null),
        Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speakerId = null),
        Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 44_000L, text = "So.", speakerId = null),
    )

/** The speakers stored for speaker ids 0 and 1. */
private val speakers1And2 = listOf(Speaker(speakerId = 0, speaker = "Speaker 1"), Speaker(speakerId = 1, speaker = "Speaker 2"))
