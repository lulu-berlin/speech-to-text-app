package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository

class UploadDuplicateSpec :
    BehaviorSpec({

        suspend fun upload(
            client: HttpClient,
            bytes: ByteArray,
            filename: String = "memo.wav",
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/tasks",
                formData =
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/x-wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            },
                        )
                    },
            )

        given("an uploaded file (queue paused)") {
            `when`("the same bytes are uploaded again") {
                then("409 is returned with duplicate-audio naming the existing task") {
                    val bytes = "THE SAME AUDIO BYTES".repeat(20).toByteArray()
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val first = upload(client, bytes)
                        first.status shouldBe HttpStatusCode.Created
                        val firstId =
                            Json
                                .parseToJsonElement(first.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive
                                .content
                                .toLong()

                        val second = upload(client, bytes)
                        second.status shouldBe HttpStatusCode.Conflict
                        second.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.duplicate-audio.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "duplicate-audio"
                        body
                            .getValue("taskId")
                            .jsonPrimitive.content
                            .toLong() shouldBe firstId

                        // Different bytes are not a duplicate.
                        upload(client, "OTHER BYTES".toByteArray()).status shouldBe HttpStatusCode.Created
                    }
                }
            }
        }
    })
