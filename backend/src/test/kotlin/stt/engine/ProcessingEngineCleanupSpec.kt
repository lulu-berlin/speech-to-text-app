package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.entries
import stt.queue.TaskStatus
import java.nio.file.Files

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
    })
