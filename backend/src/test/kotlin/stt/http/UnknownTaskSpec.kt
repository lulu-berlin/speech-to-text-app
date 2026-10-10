package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database

/**
 * The acceptance rule "every route has a test, including its 404 paths": every `/api/tasks/{id}`-derived
 * route must answer `404 not-found` for an unknown id. Transcript and turns need a `format`/nothing and a
 * paused queue does not matter; the engine commands, delete, transcript, turns and audio all share the guard.
 */
class UnknownTaskSpec :
    BehaviorSpec({
        given("an empty history (queue paused)") {
            `when`("requesting an unknown task on every task route") {
                then("each answers 404 with the not-found error body") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> stt.db.QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        suspend fun expectNotFound(path: String) {
                            val response = client.get(path)
                            response.status shouldBe HttpStatusCode.NotFound
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.json")
                            body.getValue("error").jsonPrimitive.content shouldBe "not-found"
                        }

                        expectNotFound("/api/tasks/999/transcript?format=txt")
                        expectNotFound("/api/tasks/999/turns")
                        expectNotFound("/api/tasks/999/audio")
                        listOf("pause", "resume", "cancel", "retry").forEach { command ->
                            client.post("/api/tasks/999/$command").status shouldBe HttpStatusCode.NotFound
                        }
                        client.delete("/api/tasks/999").status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }
    })
