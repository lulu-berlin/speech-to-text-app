package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Segment
import stt.db.SegmentRepository
import stt.queue.TaskStatus
import java.nio.file.Files
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
                        val plan = crashPlan(fixture, task)
                        chunks.storePlan(task, plan)
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
    })

/** The two-chunk plan the crash left behind, with a FLAC file written for each chunk. */
private suspend fun crashPlan(
    fixture: EngineFixture,
    task: Long,
): List<ExportedChunk> {
    val workDir = fixture.workDirectories.workDir(task)
    return (0 until 2).map { idx ->
        val file = workDir.resolve("chunk-$idx.flac")
        Files.writeString(file, "fake flac")
        ExportedChunk(PlannedChunk(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L), file)
    }
}
