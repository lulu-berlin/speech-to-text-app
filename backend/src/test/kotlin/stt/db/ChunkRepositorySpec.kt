package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import java.nio.file.Files
import java.nio.file.NoSuchFileException

class ChunkRepositorySpec :
    BehaviorSpec({

        given("a stored plan whose first two chunks are then marked DONE, plus a plan of another task") {
            `when`("reading the task's chunks back") {
                then("they come back in idx order with their statuses, only for that task") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val chunks = ChunkRepository(db)
                        val taskId = tasks.insert(queuedTask("talk.wav", createdAt = 1L))
                        val otherTaskId = tasks.insert(queuedTask("other.wav", createdAt = 2L))

                        chunks.storePlan(
                            taskId,
                            exportedChunks(
                                dataDir,
                                "talk",
                                PlannedChunk(0L, 30_000L),
                                PlannedChunk(30_000L, 60_000L),
                                PlannedChunk(60_000L, 75_500L),
                            ),
                        )
                        chunks.storePlan(otherTaskId, exportedChunks(dataDir, "other", PlannedChunk(0L, 5_000L)))
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

        given("a plan of three chunks with their FLAC files, and the same plan whose second chunk's file is missing") {
            `when`("each plan is stored for its own task") {
                then("the first task's plan and every FLAC are stored; the failing plan leaves neither chunks nor chunk audio") {
                    withDatabase { db ->
                        val tasks = taskRepository(db, dataDir)
                        val chunks = ChunkRepository(db)
                        val storedId = tasks.insert(queuedTask("stored.wav", createdAt = 1L))
                        val failingId = tasks.insert(queuedTask("failing.wav", createdAt = 2L))
                        val flacs = listOf(byteArrayOf(1, 2, 3), ByteArray(70_000) { (it % 251).toByte() }, byteArrayOf(9))
                        val files = flacs.mapIndexed { i, bytes -> Files.write(dataDir.resolve("chunk-$i.flac"), bytes) }
                        val plan = listOf(PlannedChunk(0L, 300_000L), PlannedChunk(300_000L, 600_000L), PlannedChunk(600_000L, 615_500L))

                        chunks.storePlan(storedId, plan.zip(files, ::ExportedChunk))
                        val withMissingFile = plan.zip(listOf(files[0], dataDir.resolve("missing.flac"), files[2]), ::ExportedChunk)
                        shouldThrow<NoSuchFileException> { chunks.storePlan(failingId, withMissingFile) }

                        chunks.findByTask(storedId).shouldBe(
                            listOf(
                                Chunk(idx = 0, startMs = 0L, endMs = 300_000L, status = ChunkStatus.PENDING),
                                Chunk(idx = 1, startMs = 300_000L, endMs = 600_000L, status = ChunkStatus.PENDING),
                                Chunk(idx = 2, startMs = 600_000L, endMs = 615_500L, status = ChunkStatus.PENDING),
                            ),
                        )
                        chunkAudio(db, storedId).shouldBe(listOf(0 to flacs[0].toList(), 1 to flacs[1].toList(), 2 to flacs[2].toList()))
                        chunks.findByTask(failingId).shouldBeEmpty()
                        chunkAudio(db, failingId).shouldBeEmpty()
                    }
                }
            }
        }
    })

/** The task's `chunk_audio` rows as (idx, FLAC bytes) in idx order, read over the raw connection. */
private fun chunkAudio(
    db: Database,
    taskId: Long,
): List<Pair<Int, List<Byte>>> =
    db.connection.prepareStatement("SELECT idx, flac FROM chunk_audio WHERE task_id = ? ORDER BY idx").use { st ->
        st.setLong(1, taskId)
        st.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1) to rows.getBytes(2).toList()) } }
    }
