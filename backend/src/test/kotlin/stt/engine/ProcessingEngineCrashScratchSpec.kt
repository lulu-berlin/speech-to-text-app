package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import stt.db.AudioRepository
import stt.db.ChunkRepository
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.TaskPhase
import stt.db.entries
import stt.queue.TaskStatus
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "(discovered) After a crash mid-chunking or mid-transcription, the next startup leaves no
 * temporary files behind": a hard kill leaves a task's scratch files under the OS temp dir (the original written out
 * for decoding, the canonical encode and decoded PCM, whisper's input and output) and a deleted task's leftovers; the
 * next engine start removes all of them before it takes any work, and the crashed tasks still finish from the
 * database alone.
 */
class ProcessingEngineCrashScratchSpec :
    BehaviorSpec({
        given("a task killed mid-chunking, one killed mid-transcription, and a deleted task's leftover scratch") {
            `when`("a new engine starts, first with the queue paused and then resumed") {
                then("the startup removes every leftover scratch file, and both tasks complete leaving none") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val scratch = fixture.scratchDir

                        // Killed mid-chunking: RUNNING in CHUNKING with no plan; its original is still stored.
                        val chunking = enqueueTask(fixture.tasks, "chunking.wav", queuePosition = 0, createdAt = 1)
                        fixture.tasks.setRunningIfQueued(chunking, startedAt = 1_000L).shouldBeTrue()
                        fixture.tasks.setPhase(chunking, TaskPhase.CHUNKING)
                        Files.writeString(scratch.originalFile(chunking), "original written out for decoding")
                        Files.writeString(scratch.chunkerDir(chunking).resolve("canonical.flac"), "half-encoded clip")
                        Files.writeString(scratch.chunkerDir(chunking).resolve("audio.s16le"), "half-decoded pcm")

                        // Killed mid-transcription: plan and clip stored, chunk 0 DONE, chunk 1's whisper input and
                        // output still on disk.
                        val transcribing = enqueueTask(fixture.tasks, "transcribing.wav", queuePosition = 1, createdAt = 2)
                        storeChunkedPlan(fixture, ChunkRepository(db), transcribing, chunkCount = 2)
                        SegmentRepository(db).completeChunk(
                            transcribing,
                            chunkIdx = 0,
                            segments =
                                listOf(
                                    Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null),
                                ),
                        )
                        AudioRepository(db).deleteOriginal(transcribing)
                        fixture.tasks.setRunningIfQueued(transcribing, startedAt = 1_000L).shouldBeTrue()
                        fixture.tasks.setPhase(transcribing, TaskPhase.TRANSCRIBING)
                        Files.writeString(scratch.taskDir(transcribing).resolve("chunk-1.json"), "partial whisper output")

                        // A task deleted while its files were being written: no row, only scratch.
                        Files.writeString(scratch.taskDir(999L).resolve("old.flac"), "orphan")

                        filesUnder(scratch.dir).isEmpty() shouldBe false
                        QueueStateRepository(db).setPaused(true)

                        // The paused queue holds both tasks, so whatever is gone was removed by the startup alone.
                        fixture.engine.start()
                        pumpUntil(2.seconds) { filesUnder(scratch.dir).isEmpty() }
                        filesUnder(scratch.dir).shouldBeEmpty()

                        // The crashed tasks need nothing from disk: they finish from the database, and leave nothing.
                        fixture.engine.resumeQueue()
                        pumpUntil(5.seconds) {
                            statusOf(fixture, chunking) == TaskStatus.COMPLETED &&
                                statusOf(fixture, transcribing) == TaskStatus.COMPLETED
                        }
                        statusOf(fixture, chunking) shouldBe TaskStatus.COMPLETED
                        statusOf(fixture, transcribing) shouldBe TaskStatus.COMPLETED
                        filesUnder(scratch.dir).shouldBeEmpty()
                        entries(dataDir.dataDir.resolve("work")).shouldBeEmpty()
                    }
                }
            }
        }
    })
