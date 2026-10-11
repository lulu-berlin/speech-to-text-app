package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.config.Config
import stt.config.Device
import stt.config.realSpeakerModelPath
import stt.db.Database
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Item 29, fix 1: an upload through the HTTP API must wake the engine itself. The worker suspends on its wake
 * channel while the queue is empty, so [UploadRoutes] has to send the wake after committing a new task —
 * regression: the task stayed QUEUED until a queue pause/resume (the engine's own integration spec seeded the
 * database and woke the engine by hand, so it never saw the HTTP path).
 */
class UploadEngineWakeSpec :
    BehaviorSpec({
        tags(Integration)

        given("a server whose engine is idle") {
            `when`("an upload arrives through the HTTP API") {
                then("the task runs to COMPLETED without any pause/resume or other engine signal") {
                    val (whisperModel, jfk) = transcriptFixtures()
                    val dataDir = tempdir("stt-upload-wake").toPath()
                    val config = Config(dataDir, realSpeakerModelPath(), whisperModel, device = Device.Cpu)
                    val clip = Files.readAllBytes(jfk)

                    testApplication {
                        application { sttApp(config) }
                        startApplication()
                        val tasks =
                            TaskRepository(
                                application.dependencies.resolve<Database>(),
                                TaskWorkDirectories(dataDir),
                            )
                        val response =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            clip,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "audio/x-wav")
                                                append(HttpHeaders.ContentDisposition, "filename=\"jfk.wav\"")
                                            },
                                        )
                                    },
                            )

                        response.status shouldBe HttpStatusCode.Created
                        val id =
                            Json
                                .parseToJsonElement(response.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive
                                .content
                                .toLong()

                        // The whole point: no wake(), no pause/resume, no reorder — the upload itself must
                        // wake the engine, or the task stays QUEUED forever. The window is 45 s: well past
                        // the ~2-20 s a real run takes (and inside Kotest 6.2.5's default 1-minute
                        // per-test watchdog), so the red is cheap and the green has real headroom.
                        val deadline = TimeSource.Monotonic.markNow() + 45.seconds
                        while (tasks.find(id)?.task?.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) &&
                            deadline.hasNotPassedNow()
                        ) {
                            delay(200.milliseconds)
                        }

                        val task = tasks.find(id).shouldNotBeNull().task
                        (task.status to task.error) shouldBe (TaskStatus.COMPLETED to null)
                    }
                }
            }
        }
    })
