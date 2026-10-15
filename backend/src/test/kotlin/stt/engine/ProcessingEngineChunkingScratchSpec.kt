package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.ScratchDir
import stt.db.entries
import stt.db.rowCount
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 44, test-list item "(discovered) Decoding to the canonical clip uses temporary files in the OS temp dir that
 * are deleted on success, on decode failure, and on cancel or delete during chunking": the original written out for
 * decoding and the chunker's scratch (canonical encode, decoded PCM, exported chunks) exist only while the task is
 * chunked. They are gone as soon as chunking succeeds — not left for the rest of the task's run — and when the
 * decode fails or the task is cancelled or deleted mid-chunking.
 */
class ProcessingEngineChunkingScratchSpec :
    BehaviorSpec({
        given("a two-chunk task whose transcription is held") {
            `when`("its chunking has succeeded and transcription starts") {
                then("the chunker's scratch and the written-out original are already gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.chunkCount = 2
                        val hold = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = hold

                        fixture.engine.start()
                        fixture.transcriber.entered.receive()

                        entries(chunkingDir(fixture.scratchDir, task)).shouldBeEmpty()
                        Files.exists(writtenOriginal(fixture.scratchDir, task)).shouldBeFalse()
                        hold.complete(Unit)
                        fixture.completed.receive() shouldBe task
                    }
                }
            }
        }

        given("a task whose decode fails after the chunker wrote its scratch") {
            `when`("the task fails") {
                then("the chunker's scratch and the written-out original are gone, and the stored original is kept") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.decodeFailsFor = task

                        fixture.engine.start()
                        fixture.failed.receive() shouldBe task

                        entries(chunkingDir(fixture.scratchDir, task)).shouldBeEmpty()
                        Files.exists(writtenOriginal(fixture.scratchDir, task)).shouldBeFalse()
                        rowCount(db, "original_audio") shouldBe 1
                    }
                }
            }
        }

        given("a task held in the middle of chunking, its scratch on disk") {
            `when`("it is cancelled") {
                then("the chunker's scratch and the written-out original are gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.hold = CompletableDeferred()

                        fixture.engine.start()
                        fixture.chunker.entered.receive()
                        entries(chunkingDir(fixture.scratchDir, task)).shouldNotBeEmpty()

                        fixture.engine.cancelTask(task) shouldBe TaskCommandOutcome.Accepted

                        entries(chunkingDir(fixture.scratchDir, task)).shouldBeEmpty()
                        Files.exists(writtenOriginal(fixture.scratchDir, task)).shouldBeFalse()
                    }
                }
            }

            `when`("it is deleted") {
                then("the chunker's scratch and the written-out original are gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.hold = CompletableDeferred()

                        fixture.engine.start()
                        fixture.chunker.entered.receive()
                        entries(chunkingDir(fixture.scratchDir, task)).shouldNotBeEmpty()

                        fixture.engine.deleteTask(task) shouldBe TaskCommandOutcome.Accepted

                        entries(chunkingDir(fixture.scratchDir, task)).shouldBeEmpty()
                        Files.exists(writtenOriginal(fixture.scratchDir, task)).shouldBeFalse()
                    }
                }
            }
        }
    })

/** The chunker's scratch directory of [task], without creating it. */
private fun chunkingDir(
    scratch: ScratchDir,
    task: Long,
): Path = scratch.taskDirPath(task).resolve("chunking")

/** The original of [task] written out for decoding, without creating its directory. */
private fun writtenOriginal(
    scratch: ScratchDir,
    task: Long,
): Path = scratch.taskDirPath(task).resolve("source")
