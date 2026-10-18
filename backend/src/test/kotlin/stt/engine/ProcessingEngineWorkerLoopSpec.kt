package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.withLoggedErrors

class ProcessingEngineWorkerLoopSpec :
    BehaviorSpec({
        given("an idle engine whose stored tasks cannot be read") {
            `when`("the reads succeed again and a task is queued") {
                then("the worker logged the failed lookup with its reason and ran the task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        // A warm-up task: its run proves the startup (the recovery write) has finished, and its queue
                        // update — the run's last step, after the completion — that the run's own writes are over, so
                        // the failed lookup below cannot land inside them.
                        val warmUp = enqueueTask(fixture.tasks, "warm-up.wav", queuePosition = 0, createdAt = 1)
                        fixture.engine.start()
                        fixture.completed.receive() shouldBe warmUp
                        while (fixture.events.receive() !is Event.QueueUpdated) {
                            // Nothing: skip the warm-up's other events.
                        }

                        // The lookup reads no table while the tasks table is away: a database that cannot be read.
                        db.writeTransaction { exec("ALTER TABLE tasks RENAME TO tasks_hidden") }
                        withLoggedErrors(ProcessingEngine::class.java.name) { errors ->
                            fixture.engine.wake()
                            val logged = errors.receive()
                            logged.formattedMessage shouldContain "Could not look for the next task"
                            logged.formattedMessage shouldContain "the database cannot be read"
                        }
                        db.writeTransaction { exec("ALTER TABLE tasks_hidden RENAME TO tasks") }

                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 1, createdAt = 2)
                        fixture.engine.taskCreated(task)

                        fixture.completed.receive() shouldBe task
                    }
                }
            }
        }
    })
