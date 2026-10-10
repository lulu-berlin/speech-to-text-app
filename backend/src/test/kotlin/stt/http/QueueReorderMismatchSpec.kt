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

class QueueReorderMismatchSpec :
    BehaviorSpec({
        given("a queue with three pending tasks (queue paused)") {
            `when`("reordering with a different set of ids") {
                then("409 reorder-mismatch is returned and the order is unchanged") {
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

                        suspend fun queueIds(): List<Long> =
                            Json
                                .parseToJsonElement(client.get("/api/queue").bodyAsText())
                                .jsonObject
                                .getValue("taskIds")
                                .jsonArray
                                .map { it.jsonPrimitive.content.toLong() }

                        queueIds() shouldBe ids

                        // A set missing a task, and a set containing an unknown id, are both mismatches.
                        val mismatches =
                            listOf(
                                listOf(ids[0], ids[1]),
                                listOf(ids[0], ids[1], 99_999),
                            )
                        for (mismatch in mismatches) {
                            val response =
                                client.put("/api/queue/order") {
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"taskIds": [${mismatch.joinToString()}]}""")
                                }
                            response.status shouldBe HttpStatusCode.Conflict
                            response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.json")
                            body.getValue("error").jsonPrimitive.content shouldBe "reorder-mismatch"
                        }

                        queueIds() shouldBe ids
                    }
                }
            }
        }
    })
