package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository

/**
 * Item 12's audio download on item 44's storage: `GET /api/tasks/{id}/audio` serves the task's stored
 * canonical clip — the header row followed by its one-second rows, in order, byte for byte — as `audio/flac`,
 * straight from the database. A task without a stored clip answers `404 no-audio` with the error body.
 */
class AudioDownloadSpec :
    BehaviorSpec({

        given("a task with a stored canonical clip (queue paused)") {
            `when`("requesting its audio twice") {
                then("the stored clip is served as audio/flac, the header then its rows in order") {
                    val dataDir = tempdir("stt-data").toPath()
                    val clip = testClip()
                    val id = taskWithTestClip(dataDir, clip)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val first = client.get("/api/tasks/$id/audio")
                        first.status shouldBe HttpStatusCode.OK
                        first.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        first.bodyAsBytes() shouldBe clip.bytes

                        val second = client.get("/api/tasks/$id/audio")
                        second.status shouldBe HttpStatusCode.OK
                        second.bodyAsBytes() shouldBe clip.bytes
                    }
                }
            }
        }

        given("a task without a stored canonical clip (queue paused)") {
            `when`("requesting its audio") {
                then("404 no-audio is returned with the error body") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            completedTask(db, "empty.wav")
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val response = client.get("/api/tasks/$id/audio")
                        response.status shouldBe HttpStatusCode.NotFound
                        val body = Json.parseToJsonElement(response.bodyAsBytes().toString(Charsets.UTF_8)).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "no-audio"
                    }
                }
            }
        }
    })
