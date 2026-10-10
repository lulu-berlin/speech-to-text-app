package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
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

class QueuePauseResumeSpec :
    BehaviorSpec({
        given("a paused queue with no tasks") {
            `when`("reading it, resuming it, and pausing it again") {
                then("GET /api/queue reflects the paused flag through the commands") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        suspend fun pausedOf(response: io.ktor.client.statement.HttpResponse): Boolean {
                            response.status shouldBe HttpStatusCode.OK
                            response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("queue.json")
                            return body
                                .getValue("paused")
                                .jsonPrimitive.content
                                .toBoolean()
                        }

                        pausedOf(client.get("/api/queue")) shouldBe true
                        pausedOf(client.post("/api/queue/resume")) shouldBe false
                        pausedOf(client.get("/api/queue")) shouldBe false
                        pausedOf(client.post("/api/queue/pause")) shouldBe true
                    }

                    Database.open(dataDir).use { db -> QueueStateRepository(db).isPaused() shouldBe true }
                }
            }
        }
    })
