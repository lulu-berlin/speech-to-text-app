package stt.engine

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.Chunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Database
import stt.db.ScratchDir
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.entries
import stt.db.rowCount
import stt.db.withoutIds
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import stt.withCapturedLogs
import java.io.IOException
import java.nio.file.Path

class ProcessingEngineCleanupSpec :
    BehaviorSpec({
        given("a task whose chunk plan cannot be stored, so it fails before chunking finished") {
            `when`("the engine gives up on it") {
                then("its scratch directory is removed and its stored original is kept") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.unwritablePlanFor = task

                        fixture.engine.start()
                        fixture.failed.receive() shouldBe task

                        statusOf(fixture, task) shouldBe TaskStatus.FAILED
                        rowCount(db, "original_audio") shouldBe 1
                        entries(fixture.scratchDir.taskDirPath(task)) shouldBe emptyList()
                    }
                }
            }
        }

        given("a two-chunk task being transcribed when it is cancelled") {
            `when`("the cancel finishes") {
                then("the chunk already done keeps its segments, the interrupted one has none, and its scratch directory is removed") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.chunkCount = 2
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))
                        // The first chunk's call runs through, so the second is the one the cancel lands in.
                        fixture.transcriber.hold = CompletableDeferred()
                        fixture.transcriber.holdFrom = 2

                        fixture.engine.start()
                        fixture.transcriber.entered
                            .receive()
                            .fileName shouldBe Path.of("chunk-0.flac")
                        fixture.transcriber.entered
                            .receive()
                            .fileName shouldBe Path.of("chunk-1.flac")

                        fixture.engine.cancelTask(task) shouldBe TaskCommandOutcome.Accepted

                        statusOf(fixture, task) shouldBe TaskStatus.CANCELED
                        chunksOf(db, task).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.PENDING)
                        segmentsOf(db, task).withoutIds() shouldBe
                            listOf(
                                Segment(
                                    chunkIdx = 0,
                                    seq = 0,
                                    startMs = 0,
                                    endMs = 1_000,
                                    text = " hello ",
                                    speakerId = null,
                                    originalText = " hello ",
                                ),
                            )
                        entries(fixture.scratchDir.taskDirPath(task)) shouldBe emptyList()
                    }
                }
            }
        }

        given("a task whose decode fails and whose scratch deletion also fails") {
            `when`("the run's cleanup throws") {
                then("the task fails with the decode error, and the delete failure is logged") {
                    withEngineDatabase { dataDir, db ->
                        val scratchDir =
                            object : ScratchDir(dataDir.dataDir) {
                                override suspend fun delete(path: Path): Unit = throw IOException("$path cannot be deleted")
                            }
                        val fixture = engineUnderTest(dataDir, db, scratchDir = scratchDir)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.decodeFailsFor = task

                        withCapturedLogs("stt.db.ScratchDir", Level.WARN) { events ->
                            fixture.engine.start()
                            fixture.failed.receive() shouldBe task

                            storedTaskOf(fixture, task).error shouldBe "fake decode failure of task $task"
                            events.map { it.formattedMessage }.any { it.contains("Could not delete") } shouldBe true
                            events
                                .mapNotNull { it.throwableProxy?.className }
                                .shouldContain(IOException::class.java.name)
                        }
                        statusOf(fixture, task) shouldBe TaskStatus.FAILED
                    }
                }
            }
        }
    })

/** The task's stored chunk plan, in `idx` order. */
private suspend fun chunksOf(
    db: Database,
    task: Long,
): List<Chunk> = ChunkRepository(db).findByTask(task)

/** The task's stored segments, in `(chunk_idx, seq)` order. */
private suspend fun segmentsOf(
    db: Database,
    task: Long,
): List<Segment> = SegmentRepository(db).findByTask(task)
