package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.MIB
import stt.db.rowCount
import stt.db.singleValue
import java.io.File
import java.io.RandomAccessFile

/** The boundary of the hand-built raw multipart bodies of the two parser-limit tests. */
private const val RAW_BOUNDARY = "----stt112"

/**
 * Item 112: Ktor's default multipart part limit (50 MiB) is below the app's own upload cap, so a file between the
 * two answered 500 while the app advertises the cap. The upload is accepted and stored whole; the fixture is a
 * generated sparse file, used here and deleted afterwards, never committed. Two 51 MiB paths are covered: a body
 * whose length is declared up front (the browser's upload) and a chunked stream with no declared length (the
 * streamed path). Both of the parser's own limits (the cap plus the margin) answer the same 413 as an over-cap
 * file, never a 500 or an unbounded read: a chunked form field overrun during the boundary scan, and a part whose
 * own `Content-Length` exceeds the bound.
 */
class UploadPartLimitSpec :
    BehaviorSpec({
        /** A 51 MiB sparse file in a fresh temp dir: zeros without holding them in memory; the caller deletes it. */
        fun sparse51MiB(): File {
            val uploads = tempdir("uploads").toPath()
            return File.createTempFile("upload-112-", ".wav", uploads.toFile()).also { source ->
                RandomAccessFile(source, "rw").use { it.setLength(51L * MIB) }
            }
        }

        /** Asserts [response] created the task and [db] holds the whole 51 MiB upload as its original. */
        suspend fun assertStoredWhole(
            response: HttpResponse,
            db: Database,
        ) {
            response.status shouldBe HttpStatusCode.Created
            val created = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            created
                .getValue("sizeBytes")
                .jsonPrimitive.content
                .toLong() shouldBe 51L * MIB
            val createdId =
                created
                    .getValue("id")
                    .jsonPrimitive.content
                    .toLong()
            singleValue(db, "SELECT task_id FROM original_audio").toLong() shouldBe createdId
            singleValue(db, "SELECT sum(length(bytes)) FROM original_audio_part").toLong() shouldBe 51L * MIB
        }

        given("a 51 MiB file under a 64 MiB upload cap (queue paused)") {
            `when`("the file is uploaded as a stream with no declared length (the chunked path)") {
                then("it is 201 with all 51 MiB stored, not the parser's 500") {
                    val dataDir = pausedDataDir()
                    val source = sparse51MiB()
                    try {
                        testApplication {
                            uploadApp(dataDir, maxUploadBytes = 64L * MIB)

                            val response =
                                client.submitFormWithBinaryData(
                                    url = "/api/tasks",
                                    formData =
                                        formData {
                                            append(
                                                "file",
                                                ChannelProvider { source.inputStream().toByteReadChannel() },
                                                Headers.build {
                                                    append(HttpHeaders.ContentType, "audio/x-wav")
                                                    append(
                                                        HttpHeaders.ContentDisposition,
                                                        "filename=\"long-upload.wav\"",
                                                    )
                                                },
                                            )
                                        },
                                )

                            assertStoredWhole(response, application.dependencies.resolve<Database>())
                        }
                    } finally {
                        source.delete()
                    }
                }
            }

            `when`("the file is uploaded with a declared Content-Length, as the browser sends it") {
                then("it is 201 with all 51 MiB stored; the declared length is under the cap") {
                    val dataDir = pausedDataDir()
                    val source = sparse51MiB()
                    try {
                        testApplication {
                            uploadApp(dataDir, maxUploadBytes = 64L * MIB)

                            // A known-size provider makes Ktor declare the body's Content-Length (the multipart
                            // envelope included), as the browser's upload does. A ChannelProvider carries no size:
                            // the body would arrive chunked, repeating the test above.
                            val response =
                                client.submitFormWithBinaryData(
                                    url = "/api/tasks",
                                    formData =
                                        formData {
                                            append(
                                                "file",
                                                InputProvider(51L * MIB) {
                                                    source.inputStream().asSource().buffered()
                                                },
                                                Headers.build {
                                                    append(HttpHeaders.ContentType, "audio/x-wav")
                                                    append(
                                                        HttpHeaders.ContentDisposition,
                                                        "filename=\"long-upload.wav\"",
                                                    )
                                                },
                                            )
                                        },
                                )

                            assertStoredWhole(response, application.dependencies.resolve<Database>())
                        }
                    } finally {
                        source.delete()
                    }
                }
            }
        }

        given("a chunked form field over the parser's bound of a 64-byte cap plus 1 MiB (queue paused)") {
            `when`("a 1 MiB + 65-byte field is streamed without a filename") {
                then("it is 413 upload-too-large and nothing is stored") {
                    val dataDir = pausedDataDir()
                    // The parser's bound is 64 + 1 MiB; one byte more is scanned as a form field, not a file.
                    val fieldBytes = MIB + 65

                    // A raw chunked body with no Content-Length and no filename: the in-process test host fails the
                    // test with the parser's own coroutine failure, so this goes through a real Netty server.
                    SseServer({ sttApp(stt.config.testConfig(dataDir, maxUploadBytes = 64)) }).use { server ->
                        val body =
                            "--$RAW_BOUNDARY\r\n".toByteArray() +
                                "Content-Disposition: form-data; name=\"huge\"\r\n\r\n".toByteArray() +
                                ByteArray(fieldBytes) +
                                "\r\n--$RAW_BOUNDARY--\r\n".toByteArray()

                        assertTooLargeRaw(postRawMultipart(server, RAW_BOUNDARY, body))
                    }

                    Database.open(dataDir).use { db ->
                        rowCount(db, "tasks") shouldBe 0
                        rowCount(db, "audio") shouldBe 0
                        rowCount(db, "original_audio") shouldBe 0
                        rowCount(db, "original_audio_part") shouldBe 0
                    }
                }
            }
        }

        given("a part declaring its own Content-Length over the parser's bound of a 64-byte cap plus 1 MiB") {
            `when`("a file part declares 1 MiB + 65 bytes while the outer body is chunked") {
                then("it is 413 upload-too-large and nothing is stored") {
                    val dataDir = pausedDataDir()

                    // A raw chunked body (no outer Content-Length) whose part header alone overruns the bound: the
                    // parser refuses at the part's Content-Length, before reading its body. The in-process test host
                    // fails the test with the parser's own coroutine failure, so this goes through a real server.
                    SseServer({ sttApp(stt.config.testConfig(dataDir, maxUploadBytes = 64)) }).use { server ->
                        val body =
                            "--$RAW_BOUNDARY\r\n".toByteArray() +
                                "Content-Disposition: form-data; name=\"file\"; filename=\"huge.wav\"\r\n".toByteArray() +
                                "Content-Type: audio/x-wav\r\n".toByteArray() +
                                "Content-Length: ${MIB + 65}\r\n\r\n".toByteArray() +
                                ByteArray(16) +
                                "\r\n--$RAW_BOUNDARY--\r\n".toByteArray()

                        assertTooLargeRaw(postRawMultipart(server, RAW_BOUNDARY, body))
                    }

                    Database.open(dataDir).use { db ->
                        rowCount(db, "tasks") shouldBe 0
                        rowCount(db, "audio") shouldBe 0
                        rowCount(db, "original_audio") shouldBe 0
                        rowCount(db, "original_audio_part") shouldBe 0
                    }
                }
            }
        }
    })
