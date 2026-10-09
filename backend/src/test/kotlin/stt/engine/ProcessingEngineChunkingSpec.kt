package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.singleValue
import stt.queue.TaskStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class ProcessingEngineChunkingSpec :
    BehaviorSpec({
        given("two queued tasks, the first of which has a chunk plan that cannot be stored") {
            `when`("the engine runs them") {
                then(
                    "nothing of the unstorable plan is kept and its original stays, while the other task's plan is stored and its original deleted",
                ) {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val broken = enqueueTask(tasks, fixture.workDirectories, "broken.wav", queuePosition = 0, createdAt = 1)
                        val good = enqueueTask(tasks, fixture.workDirectories, "good.wav", queuePosition = 1, createdAt = 2)
                        fixture.chunker.unwritablePlanFor = broken
                        val chunks = ChunkRepository(db)

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe good

                        // The failing task: the plan was stored all-or-nothing, so not even its first chunk is
                        // there, and its original (still the only copy of the audio) was not deleted.
                        statusOf(fixture, broken) shouldBe TaskStatus.FAILED
                        chunks.findByTask(broken).shouldBeEmpty()
                        singleValue(db, "SELECT count(*) FROM chunk_audio WHERE task_id = $broken").toInt() shouldBe 0
                        Files.exists(fixture.workDirectories.source(broken)) shouldBe true

                        // The other task: its chunk is stored with its FLAC, and its original is gone.
                        chunks.findByTask(good).map { it.status } shouldBe listOf(ChunkStatus.DONE)
                        chunks.copyFlacTo(good, 0, ByteArrayOutputStream())
                        Files.exists(fixture.workDirectories.source(good)) shouldBe false
                    }
                }
            }
        }
    })
