package stt.http

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import io.ktor.client.statement.HttpResponse as ClientResponse
import java.net.http.HttpClient as JavaHttpClient

/** One file part with [filename], as the upload form sends it (the content type is ignored). */
internal fun fileHeaders(filename: String): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "audio/x-wav")
        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
    }

/** An upload whose body size is known up front, so the request declares its `Content-Length`. */
internal suspend fun uploadDeclared(
    client: HttpClient,
    filename: String,
    bytes: ByteArray,
): ClientResponse =
    client.submitFormWithBinaryData(
        url = "/api/tasks",
        formData = formData { append("file", bytes, fileHeaders(filename)) },
    )

/** An upload streamed through a [ChannelProvider]: no `Content-Length`, so the body arrives chunked. */
internal suspend fun uploadChunked(
    client: HttpClient,
    filename: String,
    bytes: ByteArray,
): ClientResponse =
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

/** The `413 upload-too-large` body of a 64-byte limit against `docs/api/error.upload-too-large.json`. */
internal fun assertTooLargeBody(body: String) {
    val parsed = Json.parseToJsonElement(body).jsonObject
    parsed shouldHaveShapeOf apiExample("error.upload-too-large.json")
    parsed.getValue("error").jsonPrimitive.content shouldBe "upload-too-large"
    parsed
        .getValue("message")
        .jsonPrimitive
        .content shouldBe "The upload exceeds the maximum size of 64 bytes"
}

/** The `413 upload-too-large` contract of a 64-byte limit against `docs/api/error.upload-too-large.json`. */
internal suspend fun assertTooLarge(response: ClientResponse) {
    response.status shouldBe HttpStatusCode.PayloadTooLarge
    response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
    assertTooLargeBody(response.bodyAsText())
}

/**
 * POSTs [body] as a raw chunked multipart body (no outer `Content-Length`) with [boundary] to [server]: the shape
 * a hand-built body needs to reach the parser's own limits. Answers the raw HTTP client's response.
 */
internal fun postRawMultipart(
    server: SseServer,
    boundary: String,
    body: ByteArray,
): HttpResponse<String> =
    JavaHttpClient.newHttpClient().send(
        HttpRequest
            .newBuilder(URI("${server.base}/api/tasks"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body) })
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

/** The `413 upload-too-large` contract of the same 64-byte limit for a raw HTTP client's response. */
internal fun assertTooLargeRaw(response: HttpResponse<String>) {
    response.statusCode() shouldBe 413
    response
        .headers()
        .firstValue("Content-Type")
        .orElse("")
        .substringBefore(';') shouldBe "application/json"
    assertTooLargeBody(response.body())
}

/** A fresh data directory whose queue is paused before the app opens it, so a test upload starts no engine pass. */
internal suspend fun TestConfiguration.pausedDataDir(): Path =
    tempdir("stt-data").toPath().also { dataDir ->
        Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
    }

/** The app under test, reading and writing [dataDir] with [maxUploadBytes] as its upload cap. */
internal fun ApplicationTestBuilder.uploadApp(
    dataDir: Path,
    maxUploadBytes: Long,
) {
    application { sttApp(stt.config.testConfig(dataDir, maxUploadBytes = maxUploadBytes)) }
}
