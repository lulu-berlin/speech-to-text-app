package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SegmentRepositorySpec :
    BehaviorSpec({

        given("segments saved for a task out of order, plus a segment of another task") {
            `when`("reading the task's segments back") {
                then("they come back in seq order, only for that task") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))

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

        given("segments whose seq restarts in every chunk, saved out of order") {
            `when`("reading the task's segments back") {
                then("they come back in (chunk_idx, seq) order") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
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
    })
