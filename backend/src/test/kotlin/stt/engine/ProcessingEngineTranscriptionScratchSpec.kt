package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import stt.db.entries
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "Transcription creates one temporary input per chunk and deletes it immediately after
 * processing — no leftovers after a task": whisper's input for every chunk is one transient file in the app's
 * scratch directory under the OS temp dir (never the data directory), exactly one exists at a time, and nothing
 * is left behind once the task has completed.
 */
class ProcessingEngineTranscriptionScratchSpec :
    BehaviorSpec({
        given("a two-chunk task transcribed by the engine") {
            `when`("it runs to completion") {
                then("each whisper input is a single scratch file under the OS temp dir, and none survives the task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.chunkCount = 2

                        fixture.engine.start()
                        pumpUntil(5.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED

                        // One input per chunk, and every input lives in the app's scratch dir (item 44),
                        // outside the data directory that is the backup unit.
                        fixture.transcriber.audioFiles shouldHaveSize 2
                        fixture.transcriber.audioFiles.forEach { audio ->
                            audio.parent shouldBe fixture.scratchDir.taskDir(task)
                        }
                        fixture.scratchDir.dir
                            .startsWith(dataDir.dataDir)
                            .shouldBe(false)

                        // Exactly one input exists at a time: never the whole plan at once.
                        fixture.transcriber.inputsAtCall shouldBe listOf(1, 1)

                        // Nothing the task used survives it.
                        entries(fixture.scratchDir.taskDir(task)).shouldBeEmpty()
                    }
                }
            }
        }
    })
