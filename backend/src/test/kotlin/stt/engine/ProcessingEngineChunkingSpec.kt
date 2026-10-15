package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.db.AudioHeaderRepository
import stt.db.AudioRepository
import stt.db.AudioSecondRepository
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.singleValue
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineChunkingSpec :
    BehaviorSpec({
        given("two queued tasks, the first of which has a chunking result that cannot be stored") {
            `when`("the engine runs them") {
                then(
                    "nothing of the unstorable plan is kept and its original stays, while the other task's plan is stored and its original deleted",
                ) {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val broken = enqueueTask(tasks, "broken.wav", queuePosition = 0, createdAt = 1)
                        val good = enqueueTask(tasks, "good.wav", queuePosition = 1, createdAt = 2)
                        fixture.chunker.unwritablePlanFor = broken
                        val chunks = ChunkRepository(db)

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe good

                        // The failing task: its result was stored all-or-nothing, so not even its plan is there,
                        // and its original (still the only copy of the audio) was not deleted.
                        statusOf(fixture, broken) shouldBe TaskStatus.FAILED
                        chunks.findByTask(broken).shouldBeEmpty()
                        AudioHeaderRepository(db).read(broken) shouldBe null
                        AudioSecondRepository(db).count(broken) shouldBe 0L
                        singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id = $broken").toInt() shouldBe 1

                        // The other task: its chunk is stored with its clip, and its original is gone.
                        chunks.findByTask(good).map { it.status } shouldBe listOf(ChunkStatus.DONE)
                        AudioSecondRepository(db).count(good) shouldBe
                            fixture.chunker.canonical.rows.size
                                .toLong()
                        singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id = $good").toInt() shouldBe 0
                    }
                }
            }
        }

        given("a queued task whose original audio is gone") {
            `when`("the engine reaches its chunking phase") {
                then("the task fails with an error naming the missing original") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "gone.wav", queuePosition = 0, createdAt = 1)
                        val source = fixture.scratchDir.originalFile(task)
                        AudioRepository(db).deleteOriginal(task)

                        fixture.engine.start()
                        pumpUntil(2.seconds) { statusOf(fixture, task) == TaskStatus.FAILED }

                        statusOf(fixture, task) shouldBe TaskStatus.FAILED
                        fixture.tasks
                            .find(task)
                            ?.task
                            ?.error
                            .shouldNotBeNull() shouldContain source.toString()
                        fixture.chunker.workDirs.shouldBeEmpty()
                    }
                }
            }
        }
    })
