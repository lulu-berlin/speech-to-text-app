package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.Chunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Database
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.entries
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import java.nio.file.Files
import java.nio.file.Path

class ProcessingEngineCleanupSpec :
    BehaviorSpec({
        given("a task whose chunk plan cannot be stored, so it fails before chunking finished") {
            `when`("the engine gives up on it") {
                then("its original is kept and nothing else of its work directory is") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.unwritablePlanFor = task
                        val workDir = fixture.workDirectories.workDir(task)

                        fixture.engine.start()
                        fixture.failed.receive() shouldBe task

                        statusOf(fixture, task) shouldBe TaskStatus.FAILED
                        Files.exists(fixture.workDirectories.source(task)) shouldBe true
                        entries(workDir).map { it.fileName.toString() } shouldBe listOf("source")
                    }
                }
            }
        }

        given("a two-chunk task being transcribed when it is cancelled") {
            `when`("the cancel finishes") {
                then("the chunk already done keeps its segments, the interrupted one has none, and the work directory is empty") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        val workDir = fixture.workDirectories.workDir(task)
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
                        segmentsOf(db, task) shouldBe
                            listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null))
                        entries(workDir) shouldBe emptyList()
                        Files.exists(fixture.workDirectories.source(task)) shouldBe false
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
