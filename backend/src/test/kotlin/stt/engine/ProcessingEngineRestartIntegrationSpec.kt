package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import stt.Integration
import stt.config.Config
import stt.config.realSpeakerModelPath
import stt.config.storeCpuDevice
import stt.db.Database
import stt.db.ScratchDir
import stt.db.SegmentRepository
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.http.sttApp
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 44, test-list item "Restarting mid-queue keeps everything working with no work directories: queued, running
 * and completed tasks behave as before and finish (or resume) correctly". The real app (ffmpeg, whisper-cli tiny,
 * CAM++) is stopped while one task is transcribing, one is queued behind it and one has completed; a new app on the
 * same data directory finishes the two from the database alone, the completed one still plays, and the data
 * directory never holds anything but the database.
 */
class ProcessingEngineRestartIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the real app with one completed task, one transcribing and one queued") {
            `when`("it is stopped mid-transcription and a new app starts on the same data directory") {
                then("the transcribing and queued tasks complete, the completed one still plays, and no work directory appears") {
                    val (whisperModel, jfk) = transcriptFixtures()
                    val dataDir = tempdir("stt-restart").toPath()
                    val config = Config(dataDir, realSpeakerModelPath(), whisperModel)
                    storeCpuDevice(dataDir)
                    val clip = Files.readAllBytes(jfk)

                    // Distinct bytes per upload (the app deduplicates by content), the same decoded audio: zero bytes
                    // appended after the WAV data chunk.
                    fun upload(n: Int) = ByteArrayInputStream(clip + ByteArray(3_200 * n))

                    var completed = 0L
                    var transcribing = 0L
                    var queued = 0L
                    testApplication {
                        application { sttApp(config) }
                        startApplication()
                        val tasks = TaskRepository(application.dependencies.resolve<Database>())
                        val engine = application.dependencies.resolve<ProcessingEngine>()

                        completed = tasks.createFromUpload(queuedTask("completed.wav", createdAt = 1).copy(queuePosition = 0), upload(0))
                        engine.wake()
                        awaitUntil { tasks.find(completed)?.task?.status == TaskStatus.COMPLETED }

                        transcribing =
                            tasks.createFromUpload(queuedTask("transcribing.wav", createdAt = 2).copy(queuePosition = 1), upload(1))
                        queued = tasks.createFromUpload(queuedTask("queued.wav", createdAt = 3).copy(queuePosition = 2), upload(2))
                        engine.wake()
                        awaitUntil { tasks.find(transcribing)?.task?.phase == TaskPhase.TRANSCRIBING }
                        // The app stops here, mid-transcription.
                    }

                    // Between the runs the data directory is the database alone.
                    dataDirEntries(dataDir).filterNot { it in DATABASE_FILES }.shouldBeEmpty()
                    Database.open(dataDir).use { db ->
                        val tasks = TaskRepository(db)
                        tasks.find(completed)?.task?.status shouldBe TaskStatus.COMPLETED
                        tasks.find(transcribing)?.task?.status shouldBe TaskStatus.RUNNING
                        tasks.find(queued)?.task?.status shouldBe TaskStatus.QUEUED
                    }

                    testApplication {
                        application { sttApp(config) }
                        startApplication()
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)
                        val segments = SegmentRepository(db)

                        awaitUntil {
                            listOf(transcribing, queued).all { tasks.find(it)?.task?.status == TaskStatus.COMPLETED }
                        }
                        for (id in listOf(completed, transcribing, queued)) {
                            val task = tasks.find(id)?.task
                            (task?.status to task?.error) shouldBe (TaskStatus.COMPLETED to null)
                            segments.findByTask(id).shouldNotBeEmpty()
                        }
                        val audio = client.get("/api/tasks/$completed/audio")
                        audio.status shouldBe HttpStatusCode.OK
                        String(audio.bodyAsBytes(), 0, 4, Charsets.US_ASCII) shouldBe "fLaC"

                        dataDirEntries(dataDir).filterNot { it in DATABASE_FILES }.shouldBeEmpty()
                        scratchFiles(ScratchDir(dataDir)).shouldBeEmpty()
                    }
                }
            }
        }
    })

private val DATABASE_FILES = setOf("stt.db", "stt.db-wal", "stt.db-shm")

/** The names of everything directly in [dataDir], files and directories alike. */
private fun dataDirEntries(dataDir: Path): List<String> = Files.list(dataDir).use { paths -> paths.map { it.fileName.toString() }.toList() }

/** Every regular file left in the app's scratch directory. */
private fun scratchFiles(scratch: ScratchDir): List<Path> =
    if (!Files.isDirectory(scratch.dir)) {
        emptyList()
    } else {
        Files.walk(scratch.dir).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }
