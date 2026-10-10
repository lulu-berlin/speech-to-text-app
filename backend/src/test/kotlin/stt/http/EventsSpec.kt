package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The live events stream against a real Netty server: the in-process test host runs a request handler to
 * completion before delivering the response, which an open SSE stream never does, so this spec boots the
 * application on an ephemeral port and reads `text/event-stream` with the JDK HTTP client (no external
 * process; Netty runs in-JVM).
 */
class EventsSpec :
    BehaviorSpec({

        given("a paused queue with one QUEUED and one COMPLETED task") {
            `when`("an SSE client connects and commands run") {
                then("it first receives a snapshot, then the engine's events in emission order") {
                    val dataDir = tempdir("stt-data").toPath()
                    val (queued, completed) =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))
                            val queued =
                                tasks.createFromUpload(
                                    queuedTask("a.wav", createdAt = 1).copy(queuePosition = 0),
                                    "a".toByteArray().inputStream(),
                                )
                            val completed =
                                tasks.createFromUpload(
                                    queuedTask("b.wav", createdAt = 2).copy(
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "b".toByteArray().inputStream(),
                                )
                            queued to completed
                        }

                    val server =
                        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
                            sttApp(stt.config.testConfig(dataDir))
                        }
                    server.start(wait = false)
                    val port: Int =
                        server.engine
                            .resolvedConnectors()
                            .single()
                            .port
                    val http = HttpClient.newHttpClient()
                    val base = "http://127.0.0.1:$port"

                    fun command(
                        method: String,
                        path: String,
                    ): Int {
                        val request =
                            HttpRequest
                                .newBuilder(URI("$base$path"))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .build()
                        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                    }

                    val body =
                        http
                            .send(
                                HttpRequest.newBuilder(URI("$base/api/events")).GET().build(),
                                HttpResponse.BodyHandlers.ofInputStream(),
                            ).body()
                    try {
                        val events = SseTestReader(body.bufferedReader())
                        run {
                            val (snapshotName, snapshotData) = events.readEvent()
                            snapshotName shouldBe "snapshot"
                            val snapshot = Json.parseToJsonElement(snapshotData).jsonObject
                            snapshot shouldHaveShapeOf apiExample("snapshot.json")
                            snapshot
                                .getValue(
                                    "tasks",
                                ).jsonArray
                                .map {
                                    it.jsonObject
                                        .getValue("id")
                                        .jsonPrimitive.content
                                        .toLong()
                                } shouldBe
                                listOf(queued, completed)
                            snapshot
                                .getValue("queue")
                                .jsonObject
                                .getValue("paused")
                                .jsonPrimitive.content shouldBe "true"

                            // 2. task-updated: pausing the queued task.
                            command("POST", "/api/tasks/$queued/pause") shouldBe 200
                            val (updatedName, updatedData) = events.readEvent()
                            updatedName shouldBe "task-updated"
                            val updated = Json.parseToJsonElement(updatedData).jsonObject
                            updated
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong() shouldBe queued
                            updated.getValue("status").jsonPrimitive.content shouldBe "PAUSED"

                            // 3. queue-updated: pausing the queue again (already paused, so the flag stays true).
                            command("POST", "/api/queue/pause") shouldBe 200
                            val (queueName, queueData) = events.readEvent()
                            queueName shouldBe "queue-updated"
                            Json
                                .parseToJsonElement(queueData)
                                .jsonObject
                                .getValue("paused")
                                .jsonPrimitive.content shouldBe "true"

                            // 4. task-deleted: deleting the COMPLETED task; the delete also re-emits the queue.
                            command("DELETE", "/api/tasks/$completed") shouldBe 204
                            val (deletedName, deletedData) = events.readEvent()
                            deletedName shouldBe "task-deleted"
                            Json
                                .parseToJsonElement(deletedData)
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong() shouldBe completed
                            val (finalQueueName, _) = events.readEvent()
                            finalQueueName shouldBe "queue-updated"
                        }
                    } finally {
                        body.close()
                        server.stop(gracePeriodMillis = 100, timeoutMillis = 5_000)
                    }
                }
            }
        }
    })
