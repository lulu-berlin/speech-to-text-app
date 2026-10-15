package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

class TaskCommandsIllegalSpec :
    BehaviorSpec({

        suspend fun seed(
            dataDir: Path,
            filename: String,
            status: TaskStatus,
        ): Long =
            Database.open(dataDir).use { db ->
                QueueStateRepository(db).setPaused(true)
                TaskRepository(db).createFromUpload(
                    queuedTask(filename, createdAt = 1).copy(
                        queuePosition = 0,
                        status = status,
                    ),
                    (filename + " bytes").toByteArray().inputStream(),
                )
            }

        given("tasks whose status does not allow the command") {
            `when`("pausing a COMPLETED task, retrying a COMPLETED task, retrying a QUEUED task") {
                then("each is rejected with 409 illegal-transition and the order is left unchanged") {
                    val dataDir = tempdir("stt-data").toPath()
                    val completedId = seed(dataDir, "done.wav", TaskStatus.COMPLETED)
                    val queuedId = seed(dataDir, "todo.wav", TaskStatus.QUEUED)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val responses =
                            listOf(
                                client.post("/api/tasks/$completedId/pause"),
                                client.post("/api/tasks/$completedId/retry"),
                                client.post("/api/tasks/$queuedId/retry"),
                            )
                        for (response in responses) {
                            response.status shouldBe HttpStatusCode.Conflict
                            response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.json")
                            body.getValue("error").jsonPrimitive.content shouldBe "illegal-transition"
                        }
                        responses[0].bodyAsText().shouldContain("COMPLETED").shouldContain("PAUSED")
                    }
                }
            }
        }
    })
