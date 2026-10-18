package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
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

/**
 * Item 41, test-list item "A multipart body carrying a second file part is rejected with `400 multiple-files`, the
 * first file is not kept and no staged original remains": one file per upload — the first staged original is
 * discarded as soon as a second file part arrives, and nothing becomes a task.
 */
class UploadMultiFileSpec :
    BehaviorSpec({
        given("an upload whose multipart body carries two file parts (queue paused)") {
            `when`("the second file part arrives") {
                then("the request is 400 multiple-files and no task and no staged original remain") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        fun filePart(filename: String): Headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/x-wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            }

                        val response =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append("file", "FIRST FILE".toByteArray(), filePart("first.wav"))
                                        append("file", "SECOND FILE".toByteArray(), filePart("second.wav"))
                                    },
                            )

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "multiple-files"
                        body
                            .getValue("message")
                            .jsonPrimitive
                            .content shouldBe "Only one file may be uploaded per request"

                        val db = application.dependencies.resolve<Database>()
                        rowCount(db, "tasks") shouldBe 0
                        rowCount(db, "audio") shouldBe 0
                        rowCount(db, "original_audio") shouldBe 0
                        rowCount(db, "original_audio_part") shouldBe 0
                    }
                }
            }
        }
    })
