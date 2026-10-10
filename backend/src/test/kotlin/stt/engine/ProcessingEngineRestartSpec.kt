package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineRestartSpec :
    BehaviorSpec({
        given("a task whose crash left it RUNNING with its first chunk DONE") {
            `when`("a new engine starts against the same database") {
                then("it runs the task again, skipping the DONE chunk") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        // The crash state: a stored two-chunk plan, chunk 0 DONE with its segments, the original
                        // already deleted, and the task left RUNNING (ADR 0004).
                        val chunks = ChunkRepository(db)
                        chunks.storePlan(task, exportedPlan(fixture, task, chunkCount = 2))
                        SegmentRepository(db).completeChunk(
                            task,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        withContext(Dispatchers.IO) { Files.deleteIfExists(fixture.workDirectories.source(task)) }
                        fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L).shouldBeTrue()

                        fixture.engine.start()
                        pumpUntil(3.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }

                        // The plan was reused (chunking skipped) and the DONE chunk was not transcribed again.
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        chunks.findByTask(task).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.DONE)
                    }
                }
            }
        }
        given("a task that was chunked before the restart, whose original is gone") {
            `when`("a new engine runs it") {
                then("it resumes from its stored plan without the original") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        val chunks = ChunkRepository(db)
                        chunks.storePlan(task, exportedPlan(fixture, task, chunkCount = 2))
                        SegmentRepository(db).completeChunk(
                            task,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        withContext(Dispatchers.IO) { Files.delete(fixture.workDirectories.source(task)) }

                        // The original is not needed: the plan is reused and the DONE chunk is not
                        // transcribed again.
                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        chunks.findByTask(task).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.DONE)
                    }
                }
            }
        }
        given("a task whose plan is stored but whose original was left behind by a crash") {
            `when`("a new engine runs it") {
                then("it resumes from the stored plan and deletes the leftover original") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        val chunks = ChunkRepository(db)
                        chunks.storePlan(task, exportedPlan(fixture, task, chunkCount = 2))
                        // The crash between the plan's commit and the original's deletion: the original is
                        // still there, though the stored chunks already make it redundant.
                        Files.exists(fixture.workDirectories.source(task)) shouldBe true

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        Files.exists(fixture.workDirectories.source(task)) shouldBe false
                    }
                }
            }
        }
        given("a started task paused across a restart, with another task queued ahead of it") {
            `when`("a new engine starts") {
                then("nothing else starts while it is paused, and resuming continues it directly") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val started =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "started.wav",
                                queuePosition = 1,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val ahead =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "ahead.wav",
                                queuePosition = 0,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        // The crash state of a started, mid-transcription pause: plan stored, chunk 0 DONE,
                        // original gone, status PAUSED with its phase and start time.
                        val chunks = ChunkRepository(db)
                        chunks.storePlan(started, exportedPlan(fixture, started, chunkCount = 2))
                        SegmentRepository(db).completeChunk(
                            started,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        withContext(Dispatchers.IO) { Files.delete(fixture.workDirectories.source(started)) }
                        fixture.tasks.setRunningIfQueued(started, startedAt = 1_000L)
                        fixture.tasks.setStatus(started, TaskStatus.PAUSED)
                        fixture.tasks.setPhase(started, TaskPhase.TRANSCRIBING)

                        fixture.engine.start()
                        pumpUntil(200.milliseconds) { fixture.chunker.workDirs.isNotEmpty() }
                        fixture.chunker.workDirs.shouldBeEmpty()
                        statusOf(fixture, started) shouldBe TaskStatus.PAUSED
                        statusOf(fixture, ahead) shouldBe TaskStatus.QUEUED
                        fixture.transcriber.audioFiles.shouldBeEmpty()

                        // The resume continues the paused task directly, even though the other task is ahead.
                        fixture.engine.resumeTask(started) shouldBe TaskCommandOutcome.Accepted
                        fixture.completed.receive() shouldBe started
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        statusOf(fixture, ahead) shouldBe TaskStatus.QUEUED

                        // Once it finished, the queued task runs normally.
                        fixture.completed.receive() shouldBe ahead
                    }
                }
            }
        }
    })
