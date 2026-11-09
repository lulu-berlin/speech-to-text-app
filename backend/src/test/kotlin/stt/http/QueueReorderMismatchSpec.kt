package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import stt.db.queuedTask
import java.nio.file.Path

class QueueReorderMismatchSpec :
    BehaviorSpec({

        /** Three paused pending tasks in [dataDir], in queue order. */
        suspend fun seedThree(dataDir: Path): List<Long> =
            Database.open(dataDir).use { db ->
                QueueStateRepository(db).setPaused(true)
                val tasks = TaskRepository(db)
                listOf("a.wav", "b.wav", "c.wav").mapIndexed { index, name ->
                    tasks.createFromUpload(
                        queuedTask(name, createdAt = index.toLong() + 1).copy(queuePosition = index),
                        "$name bytes".toByteArray().inputStream(),
                    )
                }
            }

        /** The queue's pending ids as `GET /api/queue` reports them. */
        suspend fun HttpClient.queueIds(): List<Long> =
            Json
                .parseToJsonElement(get("/api/queue").bodyAsText())
                .jsonObject
                .getValue("taskIds")
                .jsonArray
                .map { it.jsonPrimitive.content.toLong() }

        /** Puts [ids] as the queue order and answers the response. */
        suspend fun HttpClient.reorder(ids: List<Long>) =
            put("/api/queue/order") {
                contentType(ContentType.Application.Json)
                setBody("""{"taskIds": [${ids.joinToString()}]}""")
            }

        given("a queue with three pending tasks (queue paused)") {
            `when`("reordering with a different set of ids") {
                then("409 reorder-mismatch is returned and the order is unchanged") {
                    val dataDir = tempdir("stt-data").toPath()
                    val ids = seedThree(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.queueIds() shouldBe ids

                        // A set missing a task, and a set containing an unknown id, are both mismatches.
                        val mismatches =
                            listOf(
                                listOf(ids[0], ids[1]),
                                listOf(ids[0], ids[1], 99_999),
                            )
                        for (mismatch in mismatches) {
                            val response = client.reorder(mismatch)
                            response.status shouldBe HttpStatusCode.Conflict
                            response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.json")
                            body.getValue("error").jsonPrimitive.content shouldBe "reorder-mismatch"
                        }

                        client.queueIds() shouldBe ids
                    }
                }
            }
        }

        given("a queue with three pending tasks (queue paused) whose ids a reorder names twice") {
            `when`("the reorder carries every pending id and one of them twice") {
                then("409 reorder-mismatch is returned and the stored order is unchanged") {
                    val dataDir = tempdir("stt-data").toPath()
                    val ids = seedThree(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.queueIds() shouldBe ids

                        // The set is the pending set, but the contract says each id once (item 12).
                        val response = client.reorder(listOf(ids[0], ids[1], ids[1], ids[2]))
                        response.status shouldBe HttpStatusCode.Conflict
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body.getValue("error").jsonPrimitive.content shouldBe "reorder-mismatch"

                        client.queueIds() shouldBe ids
                    }
                }
            }
        }
    })
