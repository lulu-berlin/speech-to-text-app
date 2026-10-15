package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import stt.db.entries
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "The data directory holds no directory besides the database's own files once a task
 * has completed": the chunker's scratch (the original written out for decoding, the canonical encode, the
 * decoded PCM and the exported chunk files) lives in the app's scratch directory under the OS temp dir, so a
 * completed task leaves the data directory holding nothing but the database.
 */
class ProcessingEngineChunkerScratchSpec :
    BehaviorSpec({
        given("a task chunked and transcribed by the engine") {
            `when`("it runs to completion") {
                then("the chunker's scratch is under the OS temp dir and no work directory is created") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)

                        fixture.engine.start()
                        pumpUntil(5.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED

                        // The chunker's scratch never lives in the data directory — the backup unit is the
                        // database alone — and nothing it wrote survives the task.
                        fixture.chunker.workDirs.shouldNotBeEmpty()
                        fixture.chunker.workDirs.forEach { dir ->
                            dir.startsWith(dataDir.dataDir).shouldBe(false)
                        }
                        entries(fixture.chunker.workDirs.single()).shouldBeEmpty()

                        // No work directory exists under the data directory at all.
                        entries(dataDir.dataDir.resolve("work")).shouldBeEmpty()
                    }
                }
            }
        }
    })
