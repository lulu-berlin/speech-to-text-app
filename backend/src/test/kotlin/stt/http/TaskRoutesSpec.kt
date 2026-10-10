package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask

class TaskRoutesSpec :
    BehaviorSpec({
        given("an empty task history") {
            `when`("requesting an unknown task") {
                then("404 is returned with a not-found error body") {
                    testApplication {
                        application { sttApp(testConfig()) }
                        val response = client.get("/api/tasks/999")
                        response.status shouldBe HttpStatusCode.NotFound
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "not-found"
                        body.getValue("message").jsonPrimitive.content shouldContain "999"
                    }
                }
            }
        }

        given("a stored QUEUED task with its upload") {
            `when`("requesting it") {
                then("200 is returned with the task") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            // The paused queue keeps the engine from starting the task, so no real ffmpeg runs.
                            QueueStateRepository(db).setPaused(true)
                            TaskRepository(db, TaskWorkDirectories(dataDir)).createFromUpload(
                                queuedTask("memo.wav", createdAt = 1_791_538_200_000).copy(
                                    queuePosition = 0,
                                    language = "de",
                                    speakersMode = SpeakersMode.FIXED,
                                    speakersCount = 3,
                                    maxSpeakers = 5,
                                    diarizationProgress = 0.0,
                                ),
                                ByteArray(1234).inputStream(),
                            )
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf taskExamples
                        body shouldBe
                            buildJsonObject {
                                put("id", id)
                                put("filename", "memo.wav")
                                put("status", "QUEUED")
                                put("phase", JsonNull)
                                put("language", "de")
                                put("speakersMode", "fixed")
                                put("speakersCount", 3)
                                put("maxSpeakers", 5)
                                put("durationMs", JsonNull)
                                put("sizeBytes", 1234)
                                put("hasAudio", false)
                                put("transcriptionProgress", 0.0)
                                put("diarizationStage", JsonNull)
                                put("diarizationProgress", 0.0)
                                put("error", JsonNull)
                                put("transcribedUntilMs", JsonNull)
                                put("createdAt", 1_791_538_200_000)
                                put("startedAt", JsonNull)
                                put("finishedAt", JsonNull)
                            }
                    }
                }
            }
        }
    })
