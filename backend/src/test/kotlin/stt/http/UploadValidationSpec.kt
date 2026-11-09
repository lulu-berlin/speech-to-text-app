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
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.rowCount

class UploadValidationSpec :
    BehaviorSpec({

        fun formBody(
            bytes: ByteArray = "FAKE WAV CONTENT".repeat(30).toByteArray(),
            filename: String = "memo.wav",
            fields: List<Pair<String, String>> = emptyList(),
            fileField: String = "file",
            extraFileField: String? = null,
        ) = formData {
            append(
                fileField,
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, "audio/x-wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                },
            )
            if (extraFileField != null) {
                append(
                    extraFileField,
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "audio/x-wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    },
                )
            }
            fields.forEach { (name, value) -> append(name, value) }
        }

        suspend fun upload(
            client: HttpClient,
            vararg fields: Pair<String, String>,
            filename: String = "memo.wav",
            bytes: ByteArray = "FAKE WAV CONTENT".repeat(30).toByteArray(),
            fileField: String = "file",
            extraFileField: String? = null,
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/tasks",
                formData =
                    formBody(
                        bytes = bytes,
                        filename = filename,
                        fields = fields.toList(),
                        fileField = fileField,
                        extraFileField = extraFileField,
                    ),
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
                        upload(client, "maxSpeakers" to "many", bytes = "many.wav".toByteArray()).expectBadRequest("invalid-max-speakers")
                        upload(client, "maxSpeakers" to "8", bytes = "three.wav".toByteArray()).let {
                            it.status shouldBe HttpStatusCode.Created
                        }
                    }
                }
            }
        }

        given("an upload whose file part is not named file") {
            `when`("the bytes are sent under recording, or under a value field's name") {
                then("each is rejected with 400 missing-file, and nothing is stored") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // Any file part that is not `file` is not this API's file field: rejected, never ignored.
                        upload(client, fileField = "recording").expectBadRequest("missing-file")
                        upload(client, fileField = "language").expectBadRequest("missing-file")
                        // A valid file part plus one under a value field's name is rejected too.
                        upload(client, extraFileField = "language").expectBadRequest("missing-file")

                        val db = application.dependencies.resolve<Database>()
                        rowCount(db, "tasks") shouldBe 0
                        rowCount(db, "original_audio") shouldBe 0
                    }
                }
            }
        }
    })
