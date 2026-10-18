package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.ChannelProvider
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
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.rowCount
import stt.db.singleValue

/**
 * Item 41's two size-limit test-list items: an upload over the configured limit is refused with `413
 * upload-too-large` naming the limit, on a declared `Content-Length` and on a chunked body, storing nothing; an
 * upload of exactly the limit is accepted when it arrives chunked, while the same file with a declared body size is
 * refused because that size also counts the multipart envelope (decided 2026-10-18, option a). The limit is
 * humanized in binary units (`KiB`, `MiB`, `GiB`) with at most one decimal, so 64 bytes read as bytes.
 */
class UploadSizeLimitSpec :
    BehaviorSpec({
        /** One file part with [filename], as the upload form sends it (the content type is ignored). */
        fun fileHeaders(filename: String): Headers =
            Headers.build {
                append(HttpHeaders.ContentType, "audio/x-wav")
                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            }

        /** An upload whose body size is known up front, so the request declares its `Content-Length`. */
        suspend fun uploadDeclared(
            client: HttpClient,
            filename: String,
            bytes: ByteArray,
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/tasks",
                formData = formData { append("file", bytes, fileHeaders(filename)) },
            )

        /** An upload streamed through a [ChannelProvider]: no `Content-Length`, so the body arrives chunked. */
        suspend fun uploadChunked(
            client: HttpClient,
            filename: String,
            bytes: ByteArray,
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/tasks",
                formData =
                    formData {
                        append(
                            "file",
                            ChannelProvider { bytes.inputStream().toByteReadChannel() },
                            fileHeaders(filename),
                        )
                    },
            )

        /** The `413 upload-too-large` contract of a 64-byte limit against `docs/api/error.upload-too-large.json`. */
        suspend fun assertTooLarge(response: HttpResponse) {
            response.status shouldBe HttpStatusCode.PayloadTooLarge
            response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body shouldHaveShapeOf apiExample("error.upload-too-large.json")
            body.getValue("error").jsonPrimitive.content shouldBe "upload-too-large"
            body
                .getValue("message")
                .jsonPrimitive
                .content shouldBe "The upload exceeds the maximum size of 64 bytes"
        }

        given("an upload limit of 64 bytes (queue paused)") {
            `when`("an upload over the limit arrives with a declared Content-Length and another as a chunked body") {
                then("both are 413 upload-too-large naming the limit, no task and no staged original remain") {
                    val bytes = ByteArray(65) { it.toByte() }
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir, maxUploadBytes = 64)) }

                        // Declared Content-Length: refused from the header, before any part is read.
                        assertTooLarge(uploadDeclared(client, "big.wav", bytes))
                        // Chunked body: no Content-Length, refused while the part is read.
                        assertTooLarge(uploadChunked(client, "big.wav", bytes))

                        val db = application.dependencies.resolve<Database>()
                        rowCount(db, "tasks") shouldBe 0
                        rowCount(db, "audio") shouldBe 0
                        rowCount(db, "original_audio") shouldBe 0
                        rowCount(db, "original_audio_part") shouldBe 0
                    }
                }
            }
        }

        given("an upload of exactly the 64-byte limit (queue paused)") {
            `when`("it arrives chunked, and a 64-byte file arrives with a declared body size") {
                then("the chunked upload is 201 with all 64 bytes stored; the declared one is 413, as decided") {
                    val bytes = ByteArray(64) { it.toByte() }
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir, maxUploadBytes = 64)) }

                        val chunked = uploadChunked(client, "exact.wav", bytes)
                        chunked.status shouldBe HttpStatusCode.Created
                        val created = Json.parseToJsonElement(chunked.bodyAsText()).jsonObject
                        created
                            .getValue("sizeBytes")
                            .jsonPrimitive.content
                            .toLong() shouldBe 64
                        val createdId =
                            created
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()

                        // A declared body size counts the multipart envelope, so it overruns the limit at 64 bytes.
                        assertTooLarge(uploadDeclared(client, "exact.wav", bytes))

                        val db = application.dependencies.resolve<Database>()
                        rowCount(db, "tasks") shouldBe 1
                        rowCount(db, "audio") shouldBe 1
                        singleValue(db, "SELECT task_id FROM original_audio").toLong() shouldBe createdId
                        singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id IS NULL").toInt() shouldBe 0
                    }
                }
            }
        }
    })
