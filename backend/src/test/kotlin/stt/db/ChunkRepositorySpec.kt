package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ChunkRepositorySpec :
    BehaviorSpec({

        given("chunks saved for a task out of order, plus a chunk of another task") {
            `when`("reading the task's chunks back") {
                then("they come back in idx order, only for that task") {
                    withDatabase { db ->
                        val tasks = taskRepository(db)
                        val chunks = ChunkRepository(db)
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

                        chunks.findByTask(taskId).shouldBe(
                            listOf(
                                Chunk(idx = 0, startMs = 0L, endMs = 30_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.DONE),
                                Chunk(idx = 2, startMs = 60_000L, endMs = 75_500L, status = ChunkStatus.PENDING),
                            ),
                        )
                    }
                }
            }
        }
    })
