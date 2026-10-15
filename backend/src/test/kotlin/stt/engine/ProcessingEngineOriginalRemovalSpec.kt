package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.queuedTask
import stt.db.rowCount
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "(discovered) Once the canonical clip is stored, the original upload is removed from the
 * database": a task created from an upload holds its original until the engine has stored the canonical clip,
 * and no longer afterwards — the clip is the task's only stored audio.
 */
class ProcessingEngineOriginalRemovalSpec :
    BehaviorSpec({
        given("a task created from an upload, so its original is stored in the database") {
            `when`("the engine chunks it") {
                then("the stored original is removed once the canonical clip is stored") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            fixture.tasks.createFromUpload(
                                queuedTask("task.wav", createdAt = 1).copy(queuePosition = 0),
                                "the original upload bytes".byteInputStream(),
                            )

                        // Before the run the original is the task's stored audio.
                        rowCount(db, "original_audio") shouldBe 1

                        fixture.engine.start()
                        pumpUntil(5.seconds) { statusOf(fixture, task) == TaskStatus.COMPLETED }
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED

                        // After it, the canonical clip is its only stored audio.
                        rowCount(db, "original_audio") shouldBe 0
                    }
                }
            }
        }
    })
