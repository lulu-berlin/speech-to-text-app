package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import stt.db.AudioRepository
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.db.rowCount
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineRetrySpec :
    BehaviorSpec({
        given("a failed task whose first chunk was done, behind nothing queued") {
            `when`("it is retried") {
                then("it is queued at the end with its error cleared, and resumes skipping the DONE chunk") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val failed =
                            enqueueTask(
                                fixture.tasks,
                                "failed.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val queued = enqueueTask(fixture.tasks, "queued.wav", queuePosition = 1, createdAt = 2)
                        val chunks = ChunkRepository(db)
                        // The failed task's crash state: plan and clip stored, chunk 0 DONE with its segments, the
                        // original gone, and the task FAILED while transcribing.
                        storeChunkedPlan(fixture, chunks, failed, chunkCount = 2)
                        SegmentRepository(db).completeChunk(
                            failed,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        AudioRepository(db).deleteOriginal(failed)
                        fixture.tasks.setRunningIfQueued(failed, startedAt = 1_000L)
                        fixture.tasks.setPhase(failed, TaskPhase.TRANSCRIBING)
                        fixture.tasks.failIfRunning(failed, "whisper-cli failed on chunk-1.flac (exit 2)", finishedAt = 9_000L)

                        // Paused, so the retried task stays queued and its cleared state is observable.
                        fixture.engine.pauseQueue()
                        fixture.engine.start()
                        fixture.engine.retryTask(failed) shouldBe TaskCommandOutcome.Accepted

                        val retried =
                            fixture.tasks
                                .find(failed)
                                ?.task
                                .shouldNotBeNull()
                        retried.status shouldBe TaskStatus.QUEUED
                        retried.error shouldBe null
                        retried.finishedAt shouldBe null
                        retried.phase shouldBe null
                        retried.queuePosition shouldBe 2 // at the end, after the queued task

                        // The queued task runs first; the retried one then skips its DONE chunk and reruns
                        // the interrupted one.
                        fixture.engine.resumeQueue()
                        fixture.completed.receive() shouldBe queued
                        fixture.completed.receive() shouldBe failed
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-0.flac", "chunk-1.flac")
                        chunks.findByTask(failed).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.DONE)
                    }
                }
            }
        }

        given("a task that failed before chunking, whose stored original was kept for the retry") {
            `when`("it is retried") {
                then("it is chunked again from the stored original and completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.tasks.setStatus(task, TaskStatus.FAILED)
                        val chunks = ChunkRepository(db)
                        rowCount(db, "original_audio") shouldBe 1
                        chunks.findByTask(task).shouldBeEmpty()

                        fixture.engine.start()
                        fixture.engine.retryTask(task) shouldBe TaskCommandOutcome.Accepted
                        pumpUntil(2.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }

                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-0.flac")
                        chunks.findByTask(task).map { it.status } shouldBe listOf(ChunkStatus.DONE)
                        rowCount(db, "original_audio") shouldBe 0
                    }
                }
            }
        }

        given("a task that completed") {
            `when`("it is retried") {
                then("the retry is refused and nothing changes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task
                        val completed =
                            fixture.tasks
                                .find(task)
                                ?.task
                                .shouldNotBeNull()

                        fixture.engine.retryTask(task) shouldBe TaskCommandOutcome.Illegal(TaskStatus.COMPLETED, TaskStatus.QUEUED)

                        val after =
                            fixture.tasks
                                .find(task)
                                ?.task
                                .shouldNotBeNull()
                        after.status shouldBe TaskStatus.COMPLETED
                        after.queuePosition shouldBe completed.queuePosition
                    }
                }
            }
        }
    })
