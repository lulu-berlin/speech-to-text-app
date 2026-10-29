package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import stt.Integration
import stt.audio.toS16
import stt.config.Config
import stt.config.realSpeakerModelPath
import stt.config.storeCpuDevice
import stt.db.AudioHeaderRepository
import stt.db.AudioSecondRepository
import stt.db.CanonicalAudio
import stt.db.Database
import stt.db.TaskRepository
import stt.db.entries
import stt.db.queuedTask
import stt.http.sttApp
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Item 44, test-list item "A fresh upload stores the full decoded clip in the database; after the task completes,
 * no audio file exists anywhere on disk (neither `work/` nor `tmp/')": after a real upload runs through the real
 * pipeline, the database holds the canonical 16 kHz mono FLAC of the clip (a header row plus one row per whole
 * second of exactly four 250 ms frames), each row's frames reassembled with the header decode to exactly the
 * PCM of that second of the source, and the data directory holds nothing but the database files.
 */
class ProcessingEngineCanonicalStorageSpec :
    BehaviorSpec({
        tags(Integration)

        given("a fresh upload through the real pipeline") {
            `when`("the task has completed") {
                then("the canonical clip is in the database, the original and scratch files are gone, and only stt.db remains") {
                    val (whisperModel, jfk) = transcriptFixtures()
                    val dataDir = tempdir("stt-e2e").toPath()
                    val config = Config(dataDir, realSpeakerModelPath(), whisperModel)
                    storeCpuDevice(dataDir)
                    val clip = Files.readAllBytes(jfk)
                    val task = queuedTask("jfk.wav", createdAt = 1L).copy(queuePosition = 0)
                    var taskId = 0L

                    // The independent reference: what a faithful decode of the upload sounds like. jfk is an 11 s clip
                    // (the fixture's tested length, item 07), so 11 s × 16 000 samples/s = 176 000 samples; the
                    // canonical clip was decoded from the same original with the same ffmpeg, so the stored rows
                    // must reproduce that stream sample for sample.
                    val expectedPcm = toS16(jfk, tempdir("stt-reference").toPath())

                    testApplication {
                        application { sttApp(config) }
                        startApplication()
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)

                        taskId = tasks.createFromUpload(task, clip.inputStream())
                        application.dependencies.resolve<ProcessingEngine>().wake()

                        val deadline = TimeSource.Monotonic.markNow() + 5.minutes
                        while (tasks.find(taskId)?.task?.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) &&
                            deadline.hasNotPassedNow()
                        ) {
                            delay(200.milliseconds)
                        }
                        (
                            tasks
                                .find(taskId)
                                .shouldNotBeNull()
                                .task.status to tasks.find(taskId)!!.task.error
                        ) shouldBe
                            (TaskStatus.COMPLETED to null)

                        val stored = AudioHeaderRepository(db).read(taskId).shouldNotBeNull()
                        val rows = AudioSecondRepository(db).read(taskId)

                        // One row per whole second, each of exactly four 250 ms frames (the canonical layout the
                        // user approved): the row count is the clip's own duration, not the implementation's.
                        rows.shouldHaveSize(11)
                        rows.forEach { row -> row.frames shouldBe 4 }

                        // Reassembled and decoded, the rows reproduce the reference PCM, second by second.
                        val scratch = tempdir("stt-scratch").toPath()
                        val joined = joinRows(stored, rows, scratch.resolve("joined.flac"))
                        toS16(joined, scratch).shouldBe(expectedPcm)

                        // After completion no audio file survives on disk: every regular file under the data
                        // directory is one of the database's own.
                        Files
                            .walk(dataDir)
                            .use { paths ->
                                paths
                                    .filter { Files.isRegularFile(it) }
                                    .map { it.name }
                                    .sorted()
                                    .toList()
                            }.shouldBe(listOf("stt.db", "stt.db-shm", "stt.db-wal"))
                    }
                }
            }
        }
    })

/** Writes [header] plus the rows' frames in order to [target], returning [target] (the reassembled FLAC). */
private fun joinRows(
    header: ByteArray,
    rows: List<CanonicalAudio>,
    target: Path,
): Path {
    Files.write(target, header)
    rows.forEach { row -> Files.write(target, row.bytes, java.nio.file.StandardOpenOption.APPEND) }
    return target
}
