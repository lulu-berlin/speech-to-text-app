package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.TranscriptionException

class ProcessingEngineFailureSpec :
    BehaviorSpec({
        given("two queued tasks of which the first one's chunk fails to transcribe") {
            `when`("the engine runs them") {
                then("the first is FAILED with the failure's message and the worker moves on to the second") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        val message = "whisper-cli failed on chunk-0.flac (exit 2):\nerror: input file not found"
                        fixture.transcriber.failNext = TranscriptionException(message)

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe second
                        val failed =
                            fixture.tasks
                                .find(first)
                                ?.task
                                .shouldNotBeNull()
                        failed.status shouldBe TaskStatus.FAILED
                        failed.error shouldBe message
                        failed.finishedAt.shouldNotBeNull()
                        failed.phase shouldBe TaskPhase.TRANSCRIBING
                    }
                }
            }
        }
    })
