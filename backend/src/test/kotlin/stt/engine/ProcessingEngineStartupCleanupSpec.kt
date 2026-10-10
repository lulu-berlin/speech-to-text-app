package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.entries
import stt.queue.TaskStatus
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineStartupCleanupSpec :
    BehaviorSpec({
        given("a data directory with pending, terminal and orphaned work directories") {
            `when`("the engine starts with the queue paused") {
                then("pending directories are kept, terminal ones are pruned or removed, and orphans are gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val pending = enqueueTask(fixture.tasks, fixture.workDirectories, "pending.wav", queuePosition = 0, createdAt = 1)
                        val failed = enqueueTask(fixture.tasks, fixture.workDirectories, "failed.wav", queuePosition = 1, createdAt = 2)
                        val canceled = enqueueTask(fixture.tasks, fixture.workDirectories, "canceled.wav", queuePosition = 2, createdAt = 3)
                        val completed =
                            enqueueTask(fixture.tasks, fixture.workDirectories, "completed.wav", queuePosition = 3, createdAt = 4)
                        val chunks = ChunkRepository(db)

                        // failed: never chunked, so its original plus a leftover sit in its directory;
                        // canceled: chunked before it stopped, so there is no original to keep;
                        // completed: its work directory is trash by now; orphan: no task row at all.
                        fixture.tasks.setStatus(failed, TaskStatus.FAILED)
                        Files.writeString(fixture.workDirectories.workDir(failed).resolve("leftover.bin"), "x")
                        chunks.storePlan(canceled, exportedPlan(fixture, canceled, chunkCount = 1))
                        withContext(Dispatchers.IO) { Files.delete(fixture.workDirectories.source(canceled)) }
                        fixture.tasks.setStatus(canceled, TaskStatus.CANCELED)
                        fixture.tasks.setCompleted(completed, finishedAt = 5_000L)
                        val orphan = fixture.workDirectories.workDir(999L)
                        withContext(Dispatchers.IO) {
                            Files.createDirectories(orphan)
                            Files.writeString(orphan.resolve("old.flac"), "x")
                        }

                        // Paused, so the pending task stays put while the cleanup runs.
                        fixture.engine.pauseQueue()
                        fixture.engine.start()
                        // The sweep lists the directories in filesystem order, so wait until every bucket
                        // has settled (pruning only ever removes, so the condition is monotone).
                        pumpUntil(2.seconds) {
                            !Files.exists(orphan) &&
                                entries(fixture.workDirectories.workDir(failed)).map { it.fileName.toString() } == listOf("source") &&
                                !Files.exists(fixture.workDirectories.workDir(canceled)) &&
                                !Files.exists(fixture.workDirectories.workDir(completed))
                        }

                        entries(fixture.workDirectories.workDir(pending)).map { it.fileName.toString() } shouldBe listOf("source")
                        entries(fixture.workDirectories.workDir(failed)).map { it.fileName.toString() } shouldBe listOf("source")
                        Files.exists(fixture.workDirectories.workDir(canceled)).shouldBeFalse()
                        Files.exists(fixture.workDirectories.workDir(completed)).shouldBeFalse()
                        Files.exists(orphan).shouldBeFalse()

                        // The kept task runs once the queue resumes, chunks from its kept original, and the
                        // engine removes its work directory at completion.
                        fixture.engine.resumeQueue()
                        fixture.completed.receive() shouldBe pending
                        Files.exists(fixture.workDirectories.workDir(pending)).shouldBeFalse()
                        chunks.findByTask(pending).map { it.status } shouldBe listOf(ChunkStatus.DONE)
                    }
                }
            }
        }
    })
