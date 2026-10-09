package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.hours

class ProcessingEngineIdleSpec :
    BehaviorSpec({
        given("an engine with an empty queue") {
            `when`("it has been idle for an hour and a task is enqueued then") {
                then("it starts that task as soon as it is signalled, without a timer having fired") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        fixture.engine.start()
                        runCurrent()

                        // Nothing is queued, so nothing may happen however much idle time passes:
                        // a polling worker would instead have wake-ups scheduled all over this hour.
                        fixture.chunker.workDirs.shouldBeEmpty()
                        advanceTimeBy(1.hours)
                        runCurrent()
                        fixture.chunker.workDirs.shouldBeEmpty()
                        val idleTime = testScheduler.currentTime

                        val id = enqueueTask(fixture.tasks, fixture.workDirectories, "idle.wav", queuePosition = 0, createdAt = 1)
                        fixture.engine.wake()

                        fixture.completed.receive() shouldBe id
                        testScheduler.currentTime shouldBe idleTime
                    }
                }
            }
        }
    })
