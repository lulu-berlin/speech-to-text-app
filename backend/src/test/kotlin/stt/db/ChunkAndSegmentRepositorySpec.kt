package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ChunkAndSegmentRepositorySpec :
    BehaviorSpec({

        given("chunks and segments saved for a task out of order, plus rows of another task") {
            `when`("reading the task's chunks and segments back") {
                then("they come back in idx / seq order, only for that task") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))

                        chunks.insertAll(
                            taskId,
                            listOf(
                                Chunk(idx = 2, startMs = 60_000L, endMs = 75_500L, status = ChunkStatus.PENDING),
                                Chunk(idx = 0, startMs = 0L, endMs = 30_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.DONE),
                            ),
                        )
                        chunks.insertAll(
                            otherTaskId,
                            listOf(Chunk(idx = 0, startMs = 0L, endMs = 5_000L, status = ChunkStatus.PENDING)),
                        )
                        segments.insertAll(
                            taskId,
                            listOf(
                                Segment(chunkIdx = 1, seq = 3, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speaker = null),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speaker = "SPEAKER_01"),
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 1, seq = 2, startMs = 30_000L, endMs = 44_000L, text = "So.", speaker = "SPEAKER_00"),
                            ),
                        )
                        segments.insertAll(
                            otherTaskId,
                            listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 4_000L, text = "Other.", speaker = null)),
                        )

                        chunks.findByTask(taskId).shouldBe(
                            listOf(
                                Chunk(idx = 0, startMs = 0L, endMs = 30_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 2, startMs = 60_000L, endMs = 75_500L, status = ChunkStatus.PENDING),
                            ),
                        )
                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0L, endMs = 11_500L, text = "Hello.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 0, seq = 1, startMs = 12_000L, endMs = 29_000L, text = "Hi.", speaker = "SPEAKER_01"),
                                Segment(chunkIdx = 1, seq = 2, startMs = 30_000L, endMs = 44_000L, text = "So.", speaker = "SPEAKER_00"),
                                Segment(chunkIdx = 1, seq = 3, startMs = 45_000L, endMs = 59_000L, text = "Bye.", speaker = null),
                            ),
                        )
                    }
                }
            }
        }
    })
