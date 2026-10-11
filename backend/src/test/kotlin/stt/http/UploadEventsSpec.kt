package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stt.db.Database
import stt.db.QueueStateRepository

/**
 * Item 29 (user's browser review): a new upload is announced on `/api/events`, as the frontend contract of
 * item 13 expects — `task-updated` with the new task, then `queue-updated` listing it. Regression: the upload
 * sent no event, so the queue list showed new uploads only after a page refresh.
 */
class UploadEventsSpec :
    BehaviorSpec({

        given("an SSE subscriber, with the queue paused so nothing is processed") {
            `when`("a file is uploaded through the HTTP API") {
                then("task-updated announces the new QUEUED task, then queue-updated lists it") {
                    val dataDir = tempdir("stt-upload-events").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        val (status, body) = server.upload("memo.wav", "audio bytes".toByteArray())
                        status shouldBe 201
                        val id =
                            Json
                                .parseToJsonElement(body)
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.long

                        val (taskName, taskData) = events.readEvent()
                        taskName shouldBe "task-updated"
                        val task = Json.parseToJsonElement(taskData).jsonObject
                        task shouldHaveShapeOf taskExamples
                        task.getValue("id").jsonPrimitive.long shouldBe id
                        task.getValue("status").jsonPrimitive.content shouldBe "QUEUED"

                        val (queueName, queueData) = events.readEvent()
                        queueName shouldBe "queue-updated"
                        Json
                            .parseToJsonElement(queueData)
                            .jsonObject
                            .getValue("taskIds")
                            .jsonArray
                            .map { it.jsonPrimitive.long } shouldBe listOf(id)
                    }
                }
            }
        }
    })
