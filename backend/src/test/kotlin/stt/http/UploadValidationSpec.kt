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

class UploadValidationSpec :
    BehaviorSpec({

        fun formBody(
            bytes: ByteArray = "FAKE WAV CONTENT".repeat(30).toByteArray(),
            filename: String = "memo.wav",
            fields: List<Pair<String, String>> = emptyList(),
        ) = formData {
            append(
                "file",
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, "audio/x-wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                },
            )
            fields.forEach { (name, value) -> append(name, value) }
        }

        suspend fun upload(
            client: HttpClient,
            vararg fields: Pair<String, String>,
            filename: String = "memo.wav",
            bytes: ByteArray = "FAKE WAV CONTENT".repeat(30).toByteArray(),
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/tasks",
                formData = formBody(bytes = bytes, filename = filename, fields = fields.toList()),
            )

        suspend fun HttpResponse.expectBadRequest(error: String) {
            status shouldBe HttpStatusCode.BadRequest
            contentType()?.withoutParameters() shouldBe ContentType.Application.Json
            val body = Json.parseToJsonElement(bodyAsText()).jsonObject
            body shouldHaveShapeOf apiExample("error.json")
            body.getValue("error").jsonPrimitive.content shouldBe error
        }

        given("uploads with an invalid request") {
            `when`(
                "a file is missing, an extension is not in the allow-list, the language is invalid, or speakers or maxSpeakers are out of range",
            ) {
                then("each is rejected with 400 and the matching error code") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        client
                            .submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData = formData { append("language", "auto") },
                            ).expectBadRequest("missing-file")
                        upload(client, filename = "memo.txt").expectBadRequest("unsupported-format")
                        upload(client, filename = "memo.MP3").let {
                            it.status shouldBe HttpStatusCode.Created
                        }
                        upload(client, "language" to "klingon").expectBadRequest("invalid-language")
                        upload(client, "language" to "en", bytes = "one.wav".toByteArray()).let {
                            it.status shouldBe HttpStatusCode.Created
                        }
                        upload(client, "speakers" to "21").expectBadRequest("invalid-speakers")
                        upload(client, "speakers" to "0").expectBadRequest("invalid-speakers")
                        upload(client, "speakers" to "many").expectBadRequest("invalid-speakers")
                        upload(client, "speakers" to "2", bytes = "two.wav".toByteArray()).let {
                            it.status shouldBe HttpStatusCode.Created
                        }
                        upload(client, "maxSpeakers" to "0").expectBadRequest("invalid-max-speakers")
                        upload(client, "maxSpeakers" to "21").expectBadRequest("invalid-max-speakers")
                        upload(client, "maxSpeakers" to "8", bytes = "three.wav".toByteArray()).let {
                            it.status shouldBe HttpStatusCode.Created
                        }
                    }
                }
            }
        }
    })
