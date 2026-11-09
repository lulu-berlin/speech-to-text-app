package stt.engine

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import stt.db.ScratchDir
import stt.queue.TaskStatus
import stt.withCapturedLogs
import java.io.IOException

/**
 * The worker's startup (item 95): a database it cannot recover fails the service's startup, but a scratch directory
 * it cannot clear is best effort — logged, and survived, so the worker still takes the queue.
 */
class ProcessingEngineStartupSpec :
    BehaviorSpec({
        given("a scratch directory whose clear fails at startup") {
            `when`("the engine starts") {
                then("the failure is logged, and the queued task still runs") {
                    withEngineDatabase { dataDir, db ->
                        val scratchDir =
                            object : ScratchDir(dataDir.dataDir) {
                                override suspend fun clear(): Unit = throw IOException("scratch $dir is read-only")
                            }
                        val fixture = engineUnderTest(dataDir, db, scratchDir = scratchDir)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)

                        withCapturedLogs("stt.engine.ProcessingEngine", Level.WARN) { events ->
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe task

                            events.map { it.level }.shouldContain(Level.WARN)
                            events.map { it.formattedMessage }.any { it.contains("Could not clear") } shouldBe true
                            events
                                .mapNotNull { it.throwableProxy?.className }
                                .shouldContain(IOException::class.java.name)
                        }
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }
    })
