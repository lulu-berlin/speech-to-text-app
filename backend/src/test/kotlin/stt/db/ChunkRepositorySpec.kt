package stt.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.audio.PlannedChunk

class ChunkRepositorySpec :
    BehaviorSpec({

        given("a stored plan whose first two chunks are then marked DONE, plus a plan of another task") {
            `when`("reading the task's chunks back") {
                then("they come back in idx order with their statuses, only for that task") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val chunks = ChunkRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))

                        storePlan(
                            db,
                            taskId,
                            listOf(PlannedChunk(0L, 30_000L), PlannedChunk(30_000L, 60_000L), PlannedChunk(60_000L, 75_500L)),
                        )
                        storePlan(db, otherTaskId, listOf(PlannedChunk(0L, 5_000L)))
                        // Marking chunks DONE is not this repository's operation; raw SQL in a committed write transaction stands in for it.
                        db.writeTransaction { exec("UPDATE chunks SET status = 'DONE' WHERE task_id = $taskId AND idx IN (0, 1)") }

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

        given("a task with a stored chunking result, and a second result for it whose clip names second 0 twice") {
            `when`("the second result is stored")
            {
                then("storing it fails, and the first result is kept whole: its windows, its header and its rows") {
                    withDatabase { db ->
                        val chunks = ChunkRepository(db)
                        val taskId = TaskRepository(db).insert(queuedTask("talk.wav", createdAt = 1L))
                        val first = CanonicalAudio(second = 0, frames = 4, bytes = byteArrayOf(1, 2, 3))
                        chunks.storeChunked(taskId, threeChunkPlan, header = byteArrayOf(9), rows = listOf(first))

                        val unstorable = CanonicalAudio(second = 0, frames = 4, bytes = byteArrayOf(7))
                        shouldThrowAny {
                            chunks.storeChunked(
                                taskId,
                                listOf(PlannedChunk(0L, 1_000L)),
                                header = byteArrayOf(8),
                                rows = listOf(unstorable, unstorable),
                            )
                        }

                        chunks.findByTask(taskId).map { PlannedChunk(it.startMs, it.endMs) } shouldBe threeChunkPlan
                        AudioHeaderRepository(db).read(taskId)?.toList() shouldBe listOf<Byte>(9)
                        AudioSecondRepository(db).read(taskId).map { it.second to it.bytes.toList() } shouldBe
                            listOf(0 to listOf<Byte>(1, 2, 3))
                    }
                }
            }
        }
    })

private val threeChunkPlan = listOf(PlannedChunk(0L, 300_000L), PlannedChunk(300_000L, 600_000L), PlannedChunk(600_000L, 615_500L))
