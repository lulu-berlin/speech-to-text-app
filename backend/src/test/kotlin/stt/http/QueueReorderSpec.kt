package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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

class QueueReorderSpec :
    BehaviorSpec({
        given("a queue with three pending tasks (queue paused)") {
            `when`("reordering with the full set of ids") {
                then("the queue order changes") {
                    val dataDir = tempdir("stt-data").toPath()
                    val ids =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))
                            listOf("a.wav", "b.wav", "c.wav").mapIndexed { index, name ->
                                tasks.createFromUpload(
                                    queuedTask(name, createdAt = index.toLong() + 1).copy(queuePosition = index),
                                    "$name bytes".toByteArray().inputStream(),
                                )
                            }
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val before = client.get("/api/queue")
                        before.status shouldBe HttpStatusCode.OK
                        before.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val beforeIds =
                            Json
                                .parseToJsonElement(before.bodyAsText())
                                .jsonObject
                                .getValue("taskIds")
                                .jsonArray
                                .map { it.jsonPrimitive.content.toLong() }
                        beforeIds shouldBe ids

                        // Reorder: c, a, b.
                        val reordered = listOf(ids[2], ids[0], ids[1])
                        val response =
                            client.put("/api/queue/order") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"taskIds": [${reordered.joinToString()}]}""")
                            }
                        response.status shouldBe HttpStatusCode.OK
                        val after =
                            Json
                                .parseToJsonElement(response.bodyAsText())
                                .jsonObject
                                .getValue("taskIds")
                                .jsonArray
                                .map { it.jsonPrimitive.content.toLong() }
                        after shouldBe reordered
                    }
                }
            }
        }
    })
