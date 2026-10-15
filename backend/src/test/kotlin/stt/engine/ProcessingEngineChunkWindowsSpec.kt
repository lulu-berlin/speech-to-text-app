package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.AudioHeaderRepository
import stt.db.AudioSecondRepository
import stt.db.ChunkRepository
import stt.db.rowCount
import stt.db.singleValue
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "(discovered) After chunking, the database holds the task's audio once — the canonical
 * clip; each chunk is stored only as its `(fromMs, toMs)` window, with no per-chunk audio".
 */
class ProcessingEngineChunkWindowsSpec :
    BehaviorSpec({
        given("a fresh upload that the chunker plans as two one-second windows") {
            `when`("it runs to completion") {
                then("its chunks are those windows, its audio is the stored clip alone, and no chunk audio is stored") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        fixture.chunker.chunkCount = 2
                        val id = enqueueTask(fixture.tasks, "talk.wav", queuePosition = 0, createdAt = 1)

                        fixture.engine.start()
                        pumpUntil(5.seconds) { statusOf(fixture, id) == TaskStatus.COMPLETED }

                        statusOf(fixture, id) shouldBe TaskStatus.COMPLETED
                        ChunkRepository(db).findByTask(id).map { it.startMs to it.endMs } shouldBe
                            listOf(0L to 1_000L, 1_000L to 2_000L)
                        AudioHeaderRepository(db).read(id)?.toList() shouldBe
                            fixture.chunker.canonical.header
                                .toList()
                        AudioSecondRepository(db).count(id) shouldBe
                            fixture.chunker.canonical.rows.size
                                .toLong()
                        singleValue(db, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'chunk_audio'") shouldBe "0"
                        rowCount(db, "original_audio") shouldBe 0
                        // Both windows were transcribed, each from its own input.
                        fixture.transcriber.audioFiles.size shouldBe 2
                    }
                }
            }
        }
    })
