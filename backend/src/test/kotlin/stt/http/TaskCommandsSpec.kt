package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

class TaskCommandsSpec :
    BehaviorSpec({

        suspend fun seed(
            dataDir: Path,
            filename: String,
            status: TaskStatus,
        ): Long =
            Database.open(dataDir).use { db ->
                // Paused: the engine must not process any of the seeded tasks during the test.
                QueueStateRepository(db).setPaused(true)
                TaskRepository(db, TaskWorkDirectories(dataDir)).createFromUpload(
                    queuedTask(filename, createdAt = 1).copy(
                        queuePosition = 0,
                        status = status,
                    ),
                    (filename + " bytes").toByteArray().inputStream(),
                )
            }

        suspend fun storedStatus(
            dataDir: Path,
            id: Long,
        ): String? =
            Database.open(dataDir).use { db ->
                TaskRepository(db, TaskWorkDirectories(dataDir))
                    .find(id)
                    ?.task
                    ?.status
                    ?.name
            }

        given("a QUEUED task (queue paused)") {
            `when`("pausing it") {
                then("200 is returned with the PAUSED task") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, "pause.wav", TaskStatus.QUEUED)
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.post("/api/tasks/$id/pause")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf taskExamples
                        body.getValue("status").jsonPrimitive.content shouldBe "PAUSED"
                        body
                            .getValue("id")
                            .jsonPrimitive.content
                            .toLong() shouldBe id
                    }
                    storedStatus(dataDir, id) shouldBe "PAUSED"
                }
            }
        }

        given("a PAUSED task (queue paused)") {
            `when`("resuming it") {
                then("200 is returned with the QUEUED task") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, "resume.wav", TaskStatus.PAUSED)
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.post("/api/tasks/$id/resume")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body.getValue("status").jsonPrimitive.content shouldBe "QUEUED"
                        body
                            .getValue("id")
                            .jsonPrimitive.content
                            .toLong() shouldBe id
                    }
                    storedStatus(dataDir, id) shouldBe "QUEUED"
                }
            }
        }

        given("a QUEUED task (queue paused)") {
            `when`("cancelling it") {
                then("200 is returned with the CANCELED task") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, "cancel.wav", TaskStatus.QUEUED)
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.post("/api/tasks/$id/cancel")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body.getValue("status").jsonPrimitive.content shouldBe "CANCELED"
                    }
                    storedStatus(dataDir, id) shouldBe "CANCELED"
                }
            }
        }

        given("a FAILED task (queue paused)") {
            `when`("retrying it") {
                then("200 is returned with the QUEUED task") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, "retry.wav", TaskStatus.FAILED)
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.post("/api/tasks/$id/retry")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body.getValue("status").jsonPrimitive.content shouldBe "QUEUED"
                    }
                    storedStatus(dataDir, id) shouldBe "QUEUED"
                }
            }
        }
    })
