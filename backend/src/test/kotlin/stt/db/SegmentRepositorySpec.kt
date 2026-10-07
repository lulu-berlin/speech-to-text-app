package stt.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

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
                                Segment(chunkIdx = 1, seq = 1, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speaker = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speaker = "SPEAKER_01"),
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 44_000L, text = "So.", speaker = "SPEAKER_00"),
                            ),
                        )
                        segments.insertAll(
                            otherTaskId,
                            listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 4_000L, text = "Other.", speaker = null)),
                        )

                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speaker = "SPEAKER_01"),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 44_000L, text = "So.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 1, seq = 1, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speaker = null),
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
                                Segment(chunkIdx = 1, seq = 1, startMs = 40_000L, endMs = 50_000L, text = "D", speaker = null),
                                Segment(chunkIdx = 0, seq = 2, startMs = 20_000L, endMs = 29_000L, text = "C", speaker = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 39_000L, text = "B2", speaker = null),
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 9_000L, text = "A", speaker = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 10_000L, endMs = 19_000L, text = "B", speaker = null),
                            ),
                        )

                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 9_000L, text = "A", speaker = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 10_000L, endMs = 19_000L, text = "B", speaker = null),
                                Segment(chunkIdx = 0, seq = 2, startMs = 20_000L, endMs = 29_000L, text = "C", speaker = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 39_000L, text = "B2", speaker = null),
                                Segment(chunkIdx = 1, seq = 1, startMs = 40_000L, endMs = 50_000L, text = "D", speaker = null),
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
                        val tasks = TaskRepository(db)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val failingTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))
                        val plan =
                            listOf(
                                Chunk(idx = 0, startMs = 0L, endMs = 30_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.PENDING),
                            )
                        chunks.insertAll(taskId, plan)
                        chunks.insertAll(failingTaskId, plan)

                        val transcribed =
                            listOf(
                                Segment(chunkIdx = 1, seq = 0, startMs = 30_000L, endMs = 37_940L, text = " So.", speaker = null),
                                Segment(chunkIdx = 1, seq = 1, startMs = 37_940L, endMs = 40_760L, text = " Bye.", speaker = null),
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
    })
