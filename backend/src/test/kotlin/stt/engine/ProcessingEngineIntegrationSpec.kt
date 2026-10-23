package stt.engine

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import stt.Integration
import stt.config.Config
import stt.config.realSpeakerModelPath
import stt.config.storeCpuDevice
import stt.db.Database
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.http.sttApp
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import stt.withCapturedLogs
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

class ProcessingEngineIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the application with the real ffmpeg, whisper-cli (ggml-tiny.en) and CAM++ model") {
            `when`("two uploads of the jfk clip are processed") {
                then("both reach COMPLETED with labelled segments, and the speaker model is loaded once") {
                    val (whisperModel, jfk) = transcriptFixtures()
                    val dataDir = tempdir("stt-e2e").toPath()
                    val config = Config(dataDir, realSpeakerModelPath(), whisperModel)
                    storeCpuDevice(dataDir)
                    val clip = Files.readAllBytes(jfk)
                    // Uploads are deduplicated by content, so the second one is the same clip with zero bytes appended
                    // after its WAV data chunk: different bytes, the same decoded audio.
                    val uploads = listOf(clip, clip + ByteArray(3_200))

                    withCapturedLogs("stt.diarization.SpeakerDiarizer", Level.INFO) { logs ->
                        testApplication {
                            application { sttApp(config) }
                            startApplication()
                            val db = application.dependencies.resolve<Database>()
                            val tasks = TaskRepository(db)
                            val segments = SegmentRepository(db)

                            val ids =
                                uploads.mapIndexed { i, bytes ->
                                    tasks.createFromUpload(
                                        queuedTask("jfk-$i.wav", createdAt = i.toLong()).copy(queuePosition = i),
                                        ByteArrayInputStream(bytes),
                                    )
                                }
                            application.dependencies.resolve<ProcessingEngine>().wake()

                            awaitUntil(every = 200.milliseconds) {
                                ids.none { tasks.find(it)?.task?.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) }
                            }

                            for (id in ids) {
                                val task = tasks.find(id).shouldNotBeNull().task
                                (task.status to task.error) shouldBe (TaskStatus.COMPLETED to null)
                                task.phase shouldBe null
                                task.progress shouldBe 1.0
                                task.diarizationStage shouldBe null
                                task.diarizationProgress shouldBe 1.0
                                task.durationMs.shouldNotBeNull()

                                val stored = segments.findByTask(id)
                                stored.shouldNotBeEmpty()
                                // The clip is whisper.cpp's jfk.wav, so the transcript is the sample's known
                                // quote (the two phrases e2e/05-transcript checks on the app's journey) and
                                // its one voice is one speaker: the same expected output on every host.
                                val transcript = stored.joinToString("") { it.text }.lowercase()
                                transcript shouldContain "ask not what your country can do for you"
                                transcript shouldContain "ask what you can do for your country"
                                segments.findSpeakers(id).size shouldBe 1
                                val speakerIds = stored.map { it.speakerId.shouldNotBeNull() }
                                segments.findSpeakers(id).map { it.speakerId } shouldBe speakerIds.distinct().sorted()
                                Files.exists(config.dataDir.resolve("work")).shouldBeFalse()
                            }
                        }
                        // One session per application run, reused for the second task.
                        logs.count { it.formattedMessage.startsWith("Loaded speaker model") } shouldBe 1
                    }
                }
            }
        }
    })
