package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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
                                fixture.workDirectories,
                                "failed.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val queued = enqueueTask(fixture.tasks, fixture.workDirectories, "queued.wav", queuePosition = 1, createdAt = 2)
                        val chunks = ChunkRepository(db)
                        // The failed task's crash state: plan stored, chunk 0 DONE with its segments, the
                        // original gone, and the task FAILED while transcribing.
                        chunks.storePlan(failed, exportedPlan(fixture, failed, chunkCount = 2))
                        SegmentRepository(db).completeChunk(
                            failed,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        withContext(Dispatchers.IO) { Files.delete(fixture.workDirectories.source(failed)) }
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
    })
