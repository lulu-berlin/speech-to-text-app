package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus

class TaskListSpec :
    BehaviorSpec({
        given("a history with pending and finished tasks (queue paused)") {
            `when`("listing the tasks") {
                then("pending tasks come in queue order, then finished ones newest finishedAt first") {
                    val dataDir = tempdir("stt-data").toPath()
                    val (queuedA, queuedB, completedOld, failedNew) =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))
                            val queuedA =
                                tasks.createFromUpload(
                                    queuedTask("a.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.QUEUED,
                                    ),
                                    "aaaa".toByteArray().inputStream(),
                                )
                            val queuedB =
                                tasks.createFromUpload(
                                    queuedTask("b.wav", createdAt = 2).copy(
                                        queuePosition = 1,
                                        status = TaskStatus.QUEUED,
                                    ),
                                    "bbbb".toByteArray().inputStream(),
                                )
                            val completedOld =
                                tasks.createFromUpload(
                                    queuedTask("c.wav", createdAt = 3).copy(
                                        queuePosition = null,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "cccc".toByteArray().inputStream(),
                                )
                            val failedNew =
                                tasks.createFromUpload(
                                    queuedTask("d.wav", createdAt = 4).copy(
                                        queuePosition = null,
                                        status = TaskStatus.FAILED,
                                        finishedAt = 2_000,
                                    ),
                                    "dddd".toByteArray().inputStream(),
                                )
                            listOf(queuedA, queuedB, completedOld, failedNew)
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("task-list.json")
                        val tasks = body.getValue("tasks").jsonArray.map { it.jsonObject }
                        tasks.map {
                            it
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()
                        } shouldContainExactly
                            listOf(queuedA, queuedB, failedNew, completedOld)
                        tasks[0].getValue("status").jsonPrimitive.content shouldBe "QUEUED"
                        tasks[1].getValue("status").jsonPrimitive.content shouldBe "QUEUED"
                        tasks[2].getValue("status").jsonPrimitive.content shouldBe "FAILED"
                        tasks[3].getValue("status").jsonPrimitive.content shouldBe "COMPLETED"
                    }
                }
            }
        }
    })
