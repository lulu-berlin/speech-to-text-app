package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import stt.db.AudioRepository
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Database
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.db.rowCount
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineRestartSpec :
    BehaviorSpec({
        given("a task whose crash left it RUNNING with its first chunk DONE") {
            `when`("a new engine starts against the same database") {
                then("it runs the task again, skipping the DONE chunk") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        // The crash state: a stored two-chunk plan with its clip, chunk 0 DONE with its segments, the
                        // original already deleted, and the task left RUNNING (ADR 0004).
                        val chunks = ChunkRepository(db)
                        storeChunkedPlan(fixture, chunks, task, chunkCount = 2)
                        SegmentRepository(db).completeChunk(
                            task,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        AudioRepository(db).deleteOriginal(task)
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
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        val chunks = ChunkRepository(db)
                        storeChunkedPlan(fixture, chunks, task, chunkCount = 2)
                        SegmentRepository(db).completeChunk(
                            task,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        AudioRepository(db).deleteOriginal(task)

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
        given("a task whose plan and clip are stored but whose original was left behind by a crash") {
            `when`("a new engine runs it") {
                then("it resumes from the stored plan and deletes the leftover original") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        val chunks = ChunkRepository(db)
                        // The crash between the clip's commit and the original's deletion: the original is
                        // still there, though the stored clip already makes it redundant.
                        storeChunkedPlan(fixture, chunks, task, chunkCount = 2)
                        rowCount(db, "original_audio") shouldBe 1

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        rowCount(db, "original_audio") shouldBe 0
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
                                "started.wav",
                                queuePosition = 1,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val ahead =
                            enqueueTask(
                                fixture.tasks,
                                "ahead.wav",
                                queuePosition = 0,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        storePausedAcrossRestart(fixture, db, started)

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
        given("a task that was PAUSED when the app stopped") {
            `when`("a new engine starts") {
                then("the task stays PAUSED, and resumes only on an explicit resume, rerunning its interrupted chunk") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        storePausedAcrossRestart(fixture, db, task)
                        val chunks = ChunkRepository(db)

                        fixture.engine.start()
                        pumpUntil(200.milliseconds) { statusOf(fixture, task) == TaskStatus.RUNNING }
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED
                        phaseOf(fixture, task) shouldBe TaskPhase.TRANSCRIBING
                        fixture.transcriber.audioFiles.shouldBeEmpty()

                        // Only an explicit resume continues it: chunk 0 is skipped and the interrupted chunk
                        // rerun (its segments were never committed).
                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        pumpUntil(2.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        chunks.findByTask(task).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.DONE)
                    }
                }
            }
        }
        given("a started task paused across a restart, with another task queued ahead of it, that is canceled") {
            `when`("a new engine is waiting on the paused task") {
                then("the queued task behind it starts without any other command") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val started =
                            enqueueTask(
                                fixture.tasks,
                                "started.wav",
                                queuePosition = 1,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val ahead =
                            enqueueTask(
                                fixture.tasks,
                                "ahead.wav",
                                queuePosition = 0,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        storePausedAcrossRestart(fixture, db, started)

                        fixture.engine.start()
                        // The worker is parked on the paused task: the queued one is ahead in the queue but
                        // starts nothing.
                        pumpUntil(200.milliseconds) { fixture.chunker.workDirs.isNotEmpty() }
                        fixture.chunker.workDirs.shouldBeEmpty()
                        statusOf(fixture, started) shouldBe TaskStatus.PAUSED
                        statusOf(fixture, ahead) shouldBe TaskStatus.QUEUED

                        // Canceling it leaves no started PAUSED task to wait on, so the worker must look again
                        // without a wake from another command.
                        fixture.engine.cancelTask(started) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, started) shouldBe TaskStatus.CANCELED
                        awaitCompleted(fixture.completed, setOf(ahead), bound = 5.seconds)
                        statusOf(fixture, ahead) shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }
    })

/**
 * The crash state of a started, mid-transcription pause (item 42): a stored two-chunk plan with its clip, chunk 0
 * DONE with its segments, the original already deleted, and the task PAUSED with its phase and start time.
 */
private suspend fun storePausedAcrossRestart(
    fixture: EngineFixture,
    db: Database,
    task: Long,
) {
    val chunks = ChunkRepository(db)
    storeChunkedPlan(fixture, chunks, task, chunkCount = 2)
    SegmentRepository(db).completeChunk(
        task,
        chunkIdx = 0,
        segments =
            listOf(
                Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
            ),
    )
    AudioRepository(db).deleteOriginal(task)
    fixture.tasks.setRunningIfQueued(task, startedAt = 1_000L)
    fixture.tasks.setStatus(task, TaskStatus.PAUSED)
    fixture.tasks.setPhase(task, TaskPhase.TRANSCRIBING)
}
